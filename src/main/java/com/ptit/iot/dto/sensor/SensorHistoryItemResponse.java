package com.ptit.iot.dto.sensor;

import java.time.LocalDateTime;

public record SensorHistoryItemResponse(
        Long id,
        Integer sensorId,
        String sensorName,
        Double value,
        LocalDateTime createdAt
) {
}
