package com.ptit.iot.service;

import com.ptit.iot.domain.enums.DeviceAction;
import com.ptit.iot.domain.enums.DeviceStatus;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class PendingCommandRegistry {
    private final ConcurrentMap<Integer, PendingCommand> pendingByDevice = new ConcurrentHashMap<>();

    public boolean hasPending(Integer deviceId) {
        return pendingByDevice.containsKey(deviceId);
    }

    public boolean register(PendingCommand command) {
        return pendingByDevice.putIfAbsent(command.deviceId(), command) == null;
    }

    public Optional<PendingCommand> takeIfMatches(Integer deviceId, DeviceStatus hardwareStatus) {
        PendingCommand command = pendingByDevice.get(deviceId);
        if (command == null) return Optional.empty();
        if (!command.action().name().equals(hardwareStatus.name())) return Optional.empty();
        return pendingByDevice.remove(deviceId, command) ? Optional.of(command) : Optional.empty();
    }

    public Optional<PendingCommand> removeIfSame(Integer deviceId, Long historyId) {
        PendingCommand command = pendingByDevice.get(deviceId);
        if (command == null || !command.historyId().equals(historyId)) return Optional.empty();
        return pendingByDevice.remove(deviceId, command) ? Optional.of(command) : Optional.empty();
    }

    public record PendingCommand(
            Long historyId,
            Integer deviceId,
            DeviceAction action,
            LocalDateTime createdAt
    ) {
    }
}
