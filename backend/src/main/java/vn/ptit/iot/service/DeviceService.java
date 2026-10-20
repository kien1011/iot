package vn.ptit.iot.service;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.*;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import vn.ptit.iot.dto.*;
import vn.ptit.iot.entity.*;
import vn.ptit.iot.mqtt.CommandPublisher;
import vn.ptit.iot.repository.*;
import vn.ptit.iot.util.TimeRange;
import vn.ptit.iot.websocket.UpdatesSocket;
@Service
public class DeviceService {
    private static final int TIMEOUT_SECONDS = 5;
    private final Map<Integer, Slot> slots = new ConcurrentHashMap<>();
    private final DeviceRepository devices;
    private final ActionHistoryRepository history;
    private final UserRepository users;
    private final TransactionTemplate tx;
    private final CommandPublisher commands;
    private final UpdatesSocket updates;
    private final ScheduledExecutorService timers;
    private final Clock clock;
    private static final class Slot { Pending pending; }
    private static final class Pending {
        final long historyId;
        final DeviceStatus action;
        final DeviceStatusResponse previous;
        long deadline;
        ScheduledFuture<?> timer;
        Pending(long historyId, DeviceStatus action, DeviceStatusResponse previous) {
            this.historyId=historyId; this.action=action; this.previous=previous;
        }
    }
    public DeviceService(DeviceRepository devices, ActionHistoryRepository history, UserRepository users,
                         TransactionTemplate tx, CommandPublisher commands, UpdatesSocket updates,
                         ScheduledExecutorService timers, Clock clock) {
        this.devices=devices; this.history=history; this.users=users; this.tx=tx;
        this.commands=commands; this.updates=updates; this.timers=timers; this.clock=clock;
    }
    public DeviceStatusResponse status(int id) {
        return tx.execute(status -> statusOf(devices.findById(id).orElseThrow()));
    }
    private DeviceStatusResponse statusOf(Device device) {
        LocalDateTime last = history.findFirstByDeviceIdAndStatusNotOrderByCreatedAtDescIdDesc(device.getId(), ActionStatus.LOADING)
            .map(ActionHistory::getCreatedAt).orElse(null);
        return new DeviceStatusResponse(device.getId(), device.getStatus(), last);
    }
    public ControlResponse control(int deviceId, DeviceStatus action, String username) {
        Slot slot = slots.computeIfAbsent(deviceId, id -> new Slot());
        synchronized (slot) {
            if (slot.pending != null) throw new ResponseStatusException(HttpStatus.CONFLICT, "Device is busy.");
            Pending pending = tx.execute(status -> {
                Device device = devices.findById(deviceId).orElseThrow();
                if (device.getStatus() == action) throw new ResponseStatusException(HttpStatus.CONFLICT, "Already in that state.");
                var user = users.findByUsername(username).orElseThrow();
                var previous = statusOf(device);
                var row = history.save(new ActionHistory(user, device, action, LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS)));
                // Pending's deadline is set below after the transaction has committed.
                return new Pending(row.getId(), action, previous);
            });
            // All history SQL is committed before exposing this pending command or publishing.
            pending.deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
            slot.pending = pending;
            pending.timer = timers.schedule(() -> timeout(slot, pending),
                Math.max(0, pending.deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            commands.publish(deviceId, action); // Nonblocking; no lock shared with the other LED.
            return new ControlResponse(pending.historyId, "LOADING", TIMEOUT_SECONDS);
        }
    }
    public void acknowledge(int deviceId, DeviceStatus actual) {
        Slot slot = slots.get(deviceId);
        if (slot == null) return;
        synchronized (slot) {
            Pending pending = slot.pending;
            if (pending == null || pending.action != actual) return;
            if (System.nanoTime() >= pending.deadline) { timeout(slot, pending); return; }
            DeviceStatusResponse result = tx.execute(status -> {
                ActionHistory row = history.findById(pending.historyId).orElseThrow();
                row.confirm();
                row.getDevice().confirm(actual);
                return new DeviceStatusResponse(deviceId, actual, row.getCreatedAt());
            });
            // A database failure exits before clearing pending or emitting success.
            slot.pending = null;
            pending.timer.cancel(false);
            updates.broadcast("device:status", result);
        }
    }
    private void timeout(Slot slot, Pending pending) {
        synchronized (slot) {
            if (slot.pending != pending) return; // An old timer cannot expire the next command.
            slot.pending = null;
            pending.timer.cancel(false);
            // No DB writes: the persisted history is still LOADING, not a fourth status.
            updates.broadcast("device:timeout", pending.previous);
        }
    }
    public PageResponse<ActionHistoryResponse> history(Integer id, DeviceStatus action, ActionStatus state,
                                                       String time, int page, int size) {
        TimeRange range = TimeRange.parse(time);
        return tx.execute(status -> PageResponse.from(history.search(id, action, state, range.start(), range.end(),
            PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))))
            .map(r -> new ActionHistoryResponse(r.getId(), r.getDevice().getName(), r.getAction(), r.getStatus(),
                r.getUser().getId(), r.getUser().getUsername(), r.getCreatedAt()))));
    }
}
