package com.ptit.iot.service;

import com.ptit.iot.domain.ActionHistory;
import com.ptit.iot.domain.Device;
import com.ptit.iot.domain.enums.ActionStatus;
import com.ptit.iot.domain.enums.DeviceAction;
import com.ptit.iot.dto.device.DeviceStatusResponse;
import com.ptit.iot.exception.ResourceNotFoundException;
import com.ptit.iot.repository.ActionHistoryRepository;
import com.ptit.iot.repository.DeviceRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@Transactional(readOnly = true)
public class DeviceQueryService {
    private final DeviceRepository deviceRepository;
    private final ActionHistoryRepository actionHistoryRepository;

    public DeviceQueryService(DeviceRepository deviceRepository,
                              ActionHistoryRepository actionHistoryRepository) {
        this.deviceRepository = deviceRepository;
        this.actionHistoryRepository = actionHistoryRepository;
    }

    public DeviceStatusResponse getStatus(Integer deviceId) {
        Device device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new ResourceNotFoundException("Device not found."));

        List<ActionHistory> successful = actionHistoryRepository.findSuccessfulForDevice(
                deviceId,
                DeviceAction.ON,
                ActionStatus.ON,
                DeviceAction.OFF,
                ActionStatus.OFF,
                PageRequest.of(0, 1)
        );
        LocalDateTime updatedAt = successful.isEmpty() ? null : successful.get(0).getCreatedAt();

        return new DeviceStatusResponse(device.getId(), device.getStatus(), updatedAt);
    }
}
