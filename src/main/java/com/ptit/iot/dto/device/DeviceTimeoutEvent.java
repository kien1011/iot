package com.ptit.iot.dto.device;

public record DeviceTimeoutEvent(
        Long actionHistoryId,
        Integer deviceId
) {
}
