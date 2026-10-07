package com.ptit.iot.dto.device;

import com.ptit.iot.domain.enums.ActionStatus;
import com.ptit.iot.domain.enums.DeviceAction;

import java.time.LocalDateTime;

public record ActionHistoryItemResponse(
        Long id,
        Integer deviceId,
        String deviceName,
        DeviceAction action,
        ActionStatus status,
        Long userId,
        String username,
        LocalDateTime createdAt
) {
}
