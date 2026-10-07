package com.ptit.iot.controller;

import com.ptit.iot.dto.common.PageResponse;
import com.ptit.iot.dto.sensor.SensorChartResponse;
import com.ptit.iot.dto.sensor.SensorHistoryItemResponse;
import com.ptit.iot.dto.sensor.SensorLatestResponse;
import com.ptit.iot.service.SensorQueryService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/sensor-data")
public class SensorDataController {
    private final SensorQueryService sensorQueryService;

    public SensorDataController(SensorQueryService sensorQueryService) {
        this.sensorQueryService = sensorQueryService;
    }

    @GetMapping("/latest")
    public ResponseEntity<?> latest() {
        return sensorQueryService.getLatest()
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/chart")
    public SensorChartResponse chart(@RequestParam(defaultValue = "20") int limit) {
        return sensorQueryService.getChart(limit);
    }

    @GetMapping("/history")
    public PageResponse<SensorHistoryItemResponse> history(
            @RequestParam(required = false) Integer sensorId,
            @RequestParam(required = false) String sensorName,
            @RequestParam(required = false) String value,
            @RequestParam(required = false) String time,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String sort
    ) {
        return sensorQueryService.getHistory(
                sensorId, sensorName, value, time, from, to, page, size, sort
        );
    }
}
