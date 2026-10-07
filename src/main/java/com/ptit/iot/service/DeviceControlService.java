package com.ptit.iot.service;

import com.ptit.iot.config.DeviceCommandProperties;
import com.ptit.iot.domain.ActionHistory;
import com.ptit.iot.domain.AppUser;
import com.ptit.iot.domain.Device;
import com.ptit.iot.domain.enums.ActionStatus;
import com.ptit.iot.domain.enums.DeviceAction;
import com.ptit.iot.domain.enums.DeviceStatus;
import com.ptit.iot.dto.device.DeviceControlResponse;
import com.ptit.iot.dto.device.DeviceStatusMqttPayload;
import com.ptit.iot.dto.device.DeviceTimeoutEvent;
import com.ptit.iot.dto.device.DeviceUpdateEvent;
import com.ptit.iot.exception.ConflictException;
import com.ptit.iot.exception.ResourceNotFoundException;
import com.ptit.iot.exception.MqttUnavailableException;
import com.ptit.iot.mqtt.MqttGateway;
import com.ptit.iot.repository.ActionHistoryRepository;
import com.ptit.iot.repository.DeviceRepository;
import com.ptit.iot.repository.UserRepository;
import com.ptit.iot.websocket.WebSocketBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;

@Service
public class DeviceControlService {
    private static final Logger log = LoggerFactory.getLogger(DeviceControlService.class);

    private final DeviceRepository deviceRepository;
    private final UserRepository userRepository;
    private final ActionHistoryRepository actionHistoryRepository;
    private final PendingCommandRegistry pendingRegistry;
    private final DeviceStateService deviceStateService;
    private final MqttGateway mqttGateway;
    private final WebSocketBroadcaster webSocketBroadcaster;
    private final TaskScheduler taskScheduler;
    private final DeviceCommandProperties properties;
    private final Clock clock;

    public DeviceControlService(DeviceRepository deviceRepository,
                                UserRepository userRepository,
                                ActionHistoryRepository actionHistoryRepository,
                                PendingCommandRegistry pendingRegistry,
                                DeviceStateService deviceStateService,
                                MqttGateway mqttGateway,
                                WebSocketBroadcaster webSocketBroadcaster,
                                TaskScheduler taskScheduler,
                                DeviceCommandProperties properties,
                                Clock clock) {
        this.deviceRepository = deviceRepository;
        this.userRepository = userRepository;
        this.actionHistoryRepository = actionHistoryRepository;
        this.pendingRegistry = pendingRegistry;
        this.deviceStateService = deviceStateService;
        this.mqttGateway = mqttGateway;
        this.webSocketBroadcaster = webSocketBroadcaster;
        this.taskScheduler = taskScheduler;
        this.properties = properties;
        this.clock = clock;
    }

    public synchronized DeviceControlResponse control(Integer deviceId,
                                                      DeviceAction action,
                                                      String username) {
        Device device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new ResourceNotFoundException("Device not found."));
        AppUser user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found."));

        if (device.getStatus().name().equals(action.name())) {
            throw new ConflictException("Device is already " + action.name() + ".");
        }
        if (pendingRegistry.hasPending(deviceId)) {
            throw new ConflictException("This device is waiting for hardware response.");
        }

        LocalDateTime createdAt = LocalDateTime.now(clock);
        ActionHistory history = new ActionHistory();
        history.setUser(user);
        history.setDevice(device);
        history.setAction(action);
        history.setStatus(ActionStatus.LOADING);
        history.setCreatedAt(createdAt);
        ActionHistory savedHistory = actionHistoryRepository.save(history);
        Long historyId = savedHistory.getId();

        PendingCommandRegistry.PendingCommand pending = new PendingCommandRegistry.PendingCommand(
                historyId, deviceId, action, createdAt
        );
        if (!pendingRegistry.register(pending)) {
            throw new ConflictException("This device is waiting for hardware response.");
        }

        int timeoutSeconds = properties.getCommandTimeoutSeconds();
        Instant expiresAt = clock.instant().plusSeconds(timeoutSeconds);
        taskScheduler.schedule(() -> expirePending(deviceId, historyId), expiresAt);

        // The current ESP8266 firmware expects exactly: deviceId:ACTION (example: 1:ON).
        // If the broker is temporarily unavailable, keep the newly-created history row as
        // LOADING and let the normal 5-second timeout path finish the request. This matches
        // the project rule that a command without hardware confirmation remains LOADING.
        try {
            mqttGateway.publishDeviceCommand(deviceId, action);
        } catch (MqttUnavailableException ex) {
            log.warn("MQTT command could not be published for historyId={}: {}", historyId, ex.getMessage());
        }

        return new DeviceControlResponse(historyId, ActionStatus.LOADING, timeoutSeconds);
    }

    public void handleHardwareStatus(DeviceStatusMqttPayload payload) {
        if (payload == null || payload.deviceId() == null || payload.status() == null) {
            log.warn("Ignored incomplete device status payload");
            return;
        }

        pendingRegistry.takeIfMatches(payload.deviceId(), payload.status())
                .ifPresentOrElse(pending -> {
                    DeviceUpdateEvent update = deviceStateService.confirm(pending, payload.status());
                    webSocketBroadcaster.broadcast("device:update", update);
                }, () -> log.warn(
                        "Ignored late, unsolicited, or mismatched device status: deviceId={}, status={}",
                        payload.deviceId(), payload.status()
                ));
    }

    private void expirePending(Integer deviceId, Long historyId) {
        pendingRegistry.removeIfSame(deviceId, historyId).ifPresent(pending -> {
            // Per the report, a command without hardware confirmation keeps the history row
            // permanently at LOADING and leaves devices.status unchanged. The timeout event
            // only tells the frontend to stop displaying the pending LOADING state.
            log.info("Device command timed out: historyId={}, deviceId={}", historyId, deviceId);
            webSocketBroadcaster.broadcast(
                    "device:timeout",
                    new DeviceTimeoutEvent(historyId, deviceId)
            );
        });
    }
}
