package com.ptit.iot.dto.device;

import com.ptit.iot.domain.enums.DeviceStatus;

public record DeviceStatusMqttPayload(Integer deviceId, DeviceStatus status) {
}
