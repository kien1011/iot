package vn.ptit.iot.controller;

import org.springframework.web.bind.annotation.*;
import vn.ptit.iot.dto.*;
import vn.ptit.iot.service.SensorService;

@RestController
@RequestMapping("/api/sensor-data")
public class SensorController {
    private final SensorService sensors;
    public SensorController(SensorService sensors) { this.sensors = sensors; }
    @GetMapping("/latest") public SensorReadingResponse latest() { return sensors.latest(); }
    // The demo's contract is always the most recent 20 points per sensor.
    @GetMapping("/chart") public ChartResponse chart() { return sensors.chart(); }
    @GetMapping("/history") public PageResponse<SensorHistoryResponse> history(
            @RequestParam(required = false) Integer sensorId, @RequestParam(required = false) String value,
            @RequestParam(required = false) String time, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return sensors.history(sensorId, value, time, page, size);
    }
}
