package com.ptit.iot.dto.sensor;

import java.time.LocalDateTime;

public record SensorPointResponse(LocalDateTime time, Double value) {
}
