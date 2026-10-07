package com.ptit.iot.dto.device;

import com.ptit.iot.domain.enums.DeviceAction;
import jakarta.validation.constraints.NotNull;

public record DeviceControlRequest(
        @NotNull(message = "Action is required.") DeviceAction action
) {
}
