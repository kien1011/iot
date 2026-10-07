package com.ptit.iot.dto.device;

import com.ptit.iot.domain.enums.ActionStatus;

public record DeviceControlResponse(
        Long id,
        ActionStatus status,
        int timeoutSeconds
) {
}
