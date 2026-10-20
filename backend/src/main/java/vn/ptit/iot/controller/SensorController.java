package vn.ptit.iot.controller;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import vn.ptit.iot.dto.*;
import vn.ptit.iot.service.SensorService;
@RestController
@RequestMapping("/api/sensor-data")
public class SensorController {
    private final SensorService sensors;
    public SensorController(SensorService sensors) { this.sensors=sensors; }
    @GetMapping("/latest") public SensorSnapshot latest() { return sensors.latest(); }
    @GetMapping("/chart") public ChartResponse chart() { return sensors.chart(); }
    @GetMapping("/history") public PageResponse<SensorHistoryResponse> history(
        @RequestParam(required=false) Integer sensorId, @RequestParam(required=false) String value,
        @RequestParam(required=false) String time, @RequestParam(defaultValue="0") int page,
        @RequestParam(defaultValue="10") int size) {
        try { return sensors.history(sensorId, value, time, page, size); }
        catch (IllegalArgumentException ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid search input.", ex); }
    }
}
