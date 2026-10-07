package com.ptit.iot.dto.sensor;

import java.time.LocalDateTime;

public record SensorLatestResponse(
        Double temperature,
        Double humidity,
        Double light,
        LocalDateTime time
) {
}
