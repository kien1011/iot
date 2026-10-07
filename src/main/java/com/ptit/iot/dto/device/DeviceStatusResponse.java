package com.ptit.iot.dto.device;

import com.ptit.iot.domain.enums.DeviceStatus;

import java.time.LocalDateTime;

public record DeviceStatusResponse(
        Integer deviceId,
        DeviceStatus status,
        LocalDateTime updatedAt
) {
}
