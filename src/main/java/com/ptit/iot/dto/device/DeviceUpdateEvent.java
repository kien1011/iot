package com.ptit.iot.dto.device;

import com.ptit.iot.domain.enums.DeviceStatus;

import java.time.LocalDateTime;

public record DeviceUpdateEvent(
        Long actionHistoryId,
        Integer deviceId,
        DeviceStatus status,
        LocalDateTime updatedAt
) {
}
