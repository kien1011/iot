package com.ptit.iot.service;

import com.ptit.iot.domain.ActionHistory;
import com.ptit.iot.domain.Device;
import com.ptit.iot.domain.enums.ActionStatus;
import com.ptit.iot.domain.enums.DeviceStatus;
import com.ptit.iot.dto.device.DeviceUpdateEvent;
import com.ptit.iot.exception.ResourceNotFoundException;
import com.ptit.iot.repository.ActionHistoryRepository;
import com.ptit.iot.repository.DeviceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeviceStateService {
    private final ActionHistoryRepository actionHistoryRepository;
    private final DeviceRepository deviceRepository;

    public DeviceStateService(ActionHistoryRepository actionHistoryRepository,
                              DeviceRepository deviceRepository) {
        this.actionHistoryRepository = actionHistoryRepository;
        this.deviceRepository = deviceRepository;
    }

    @Transactional
    public DeviceUpdateEvent confirm(PendingCommandRegistry.PendingCommand pending,
                                     DeviceStatus hardwareStatus) {
        ActionHistory history = actionHistoryRepository.findById(pending.historyId())
                .orElseThrow(() -> new ResourceNotFoundException("Action history not found."));
        Device device = deviceRepository.findById(pending.deviceId())
                .orElseThrow(() -> new ResourceNotFoundException("Device not found."));

        ActionStatus confirmedStatus = ActionStatus.valueOf(hardwareStatus.name());
        history.setStatus(confirmedStatus);
        device.setStatus(hardwareStatus);

        actionHistoryRepository.save(history);
        deviceRepository.save(device);

        // Per project convention, updatedAt is the created_at of the latest successful history row.
        return new DeviceUpdateEvent(history.getId(), device.getId(), hardwareStatus, history.getCreatedAt());
    }
}
