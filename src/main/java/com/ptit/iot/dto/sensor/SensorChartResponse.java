package com.ptit.iot.dto.sensor;

import java.util.List;

public record SensorChartResponse(
        List<SensorPointResponse> temperature,
        List<SensorPointResponse> humidity,
        List<SensorPointResponse> light
) {
}
