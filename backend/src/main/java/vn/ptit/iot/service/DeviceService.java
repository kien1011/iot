package vn.ptit.iot.service;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ScheduledFuture;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import vn.ptit.iot.dto.*;
import vn.ptit.iot.entity.*;
import vn.ptit.iot.mqtt.MqttCommandPublisher;
import vn.ptit.iot.repository.*;
import vn.ptit.iot.util.TimeRange;
import vn.ptit.iot.websocket.LiveUpdates;

@Service
public class DeviceService {
    private static final int TIMEOUT_SECONDS = 5;
    // Each slot is its own monitor. No global five-second lock.
    private final Map<Integer, Slot> slots = Map.of(1, new Slot(), 2, new Slot());
    private final DeviceRepository devices;
    private final UserRepository users;
    private final ActionHistoryRepository history;
    private final TransactionTemplate transactions;
    private final TaskScheduler scheduler;
    private final Clock clock;
    private final MqttCommandPublisher commands;
    private final LiveUpdates live;

    public DeviceService(DeviceRepository devices, UserRepository users, ActionHistoryRepository history,
                         TransactionTemplate transactions, TaskScheduler scheduler, Clock clock,
                         MqttCommandPublisher commands, LiveUpdates live) {
        this.devices = devices; this.users = users; this.history = history; this.transactions = transactions;
        this.scheduler = scheduler; this.clock = clock; this.commands = commands; this.live = live;
    }

    public ControlResponse control(int deviceId, DeviceState action, Long userId) {
        var slot = slots.get(deviceId);
        synchronized (slot) {
            if (slot.pending != null) throw new ResponseStatusException(HttpStatus.CONFLICT);
            Long historyId = transactions.execute(tx -> {
                var device = devices.findById(deviceId).orElseThrow();
                if (device.getStatus() == action) throw new ResponseStatusException(HttpStatus.CONFLICT);
                var row = new ActionHistory(users.getReferenceById(userId), device, action, now());
                return history.save(row).getId();
            });
            // The LOADING row has committed before installing pending and publishing.
            var pending = new Pending(historyId, action, clock.instant().plusSeconds(TIMEOUT_SECONDS));
            slot.pending = pending;
            pending.timer = scheduler.schedule(() -> timeout(deviceId, slot, pending), pending.deadline);
            commands.publish(deviceId, action);
            return new ControlResponse(historyId, ActionStatus.LOADING, TIMEOUT_SECONDS);
        }
    }

    public void acknowledge(int deviceId, DeviceState status) {
        var slot = slots.get(deviceId);
        if (slot == null) return;
        synchronized (slot) {
            var pending = slot.pending;
            if (pending == null || pending.action != status) return;
            if (!clock.instant().isBefore(pending.deadline)) {
                finish(deviceId, slot, pending, false);
                return;
            }
            finish(deviceId, slot, pending, true);
        }
    }

    private void timeout(int deviceId, Slot slot, Pending expected) {
        synchronized (slot) {
            // Object identity prevents a canceled/old timer affecting a later command.
            if (slot.pending != expected) return;
            finish(deviceId, slot, expected, false);
        }
    }

    private void finish(int deviceId, Slot slot, Pending pending, boolean success) {
        var result = transactions.execute(tx -> {
            var row = history.findById(pending.historyId).orElseThrow();
            var device = devices.findById(deviceId).orElseThrow();
            row.finish(success ? ActionStatus.SUCCESS : ActionStatus.FAILED);
            if (success) device.confirm(pending.action, now());
            return DeviceStatusResponse.from(device);
        });
        slot.pending = null;
        if (pending.timer != null) pending.timer.cancel(false);
        // History and device have committed together before any client sees the result.
        live.broadcast(success ? "device:status" : "device:timeout", result);
    }

    private LocalDateTime now() { return LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS); }

    @Transactional(readOnly = true)
    public DeviceStatusResponse status(int deviceId) {
        return DeviceStatusResponse.from(devices.findById(deviceId).orElseThrow());
    }

    @Transactional(readOnly = true)
    public PageResponse<ActionHistoryResponse> history(Integer deviceId, DeviceState action, ActionStatus status,
                                                       String timeText, int page, int size) {
        var time = TimeRange.parse(timeText);
        Specification<ActionHistory> spec = (root, query, cb) -> {
            var conditions = new ArrayList<jakarta.persistence.criteria.Predicate>();
            if (deviceId != null) conditions.add(cb.equal(root.get("device").get("id"), deviceId));
            if (action != null) conditions.add(cb.equal(root.get("action"), action));
            if (status != null) conditions.add(cb.equal(root.get("status"), status));
            if (time != null) {
                conditions.add(cb.greaterThanOrEqualTo(root.get("createdAt"), time.start()));
                // MySQL DATETIME ends at year 9999: year 10000 is an implicit storage bound.
                if (time.end().getYear() <= 9999) conditions.add(cb.lessThan(root.get("createdAt"), time.end()));
            }
            return cb.and(conditions.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
        return PageResponse.from(history.findAll(spec, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id")))
            .map(ActionHistoryResponse::from));
    }

    private static final class Slot { private Pending pending; }
    private static final class Pending {
        private final Long historyId;
        private final DeviceState action;
        private final Instant deadline;
        private ScheduledFuture<?> timer;
        private Pending(Long historyId, DeviceState action, Instant deadline) {
            this.historyId = historyId; this.action = action; this.deadline = deadline;
        }
    }
}
