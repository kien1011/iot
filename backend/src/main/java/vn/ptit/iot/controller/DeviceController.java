package vn.ptit.iot.controller;
import java.security.Principal;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import vn.ptit.iot.dto.*;
import vn.ptit.iot.entity.*;
import vn.ptit.iot.service.DeviceService;
@RestController
@RequestMapping("/api/devices")
public class DeviceController {
    private final DeviceService devices;
    public DeviceController(DeviceService devices) { this.devices=devices; }
    @GetMapping("/{deviceId}/status") public DeviceStatusResponse status(@PathVariable int deviceId) { return devices.status(deviceId); }
    @PostMapping("/{deviceId}/control") public ControlResponse control(@PathVariable int deviceId,
        @RequestBody ControlRequest request, Principal principal) {
        return devices.control(deviceId, request.action(), principal.getName());
    }
    @GetMapping("/action-history") public PageResponse<ActionHistoryResponse> history(
        @RequestParam(required=false) Integer deviceId, @RequestParam(required=false) DeviceStatus action,
        @RequestParam(required=false) ActionStatus status, @RequestParam(required=false) String time,
        @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="10") int size) {
        try { return devices.history(deviceId, action, status, time, page, size); }
        catch (IllegalArgumentException ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid search input.", ex); }
    }
}
