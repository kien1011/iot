package vn.ptit.iot.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import vn.ptit.iot.dto.*;
import vn.ptit.iot.entity.*;
import vn.ptit.iot.service.DeviceService;

@RestController
@RequestMapping("/api/devices")
public class DeviceController {
    private final DeviceService devices;
    public DeviceController(DeviceService devices) { this.devices = devices; }
    @GetMapping("/{deviceId}/status") public DeviceStatusResponse status(@PathVariable int deviceId) {
        return devices.status(deviceId);
    }
    @PostMapping("/{deviceId}/control") public ControlResponse control(@PathVariable int deviceId,
            @RequestBody ControlRequest request, @AuthenticationPrincipal AuthenticatedUser user) {
        return devices.control(deviceId, request.action(), user.id());
    }
    @GetMapping("/action-history") public PageResponse<ActionHistoryResponse> history(
            @RequestParam(required = false) Integer deviceId, @RequestParam(required = false) DeviceState action,
            @RequestParam(required = false) ActionStatus status, @RequestParam(required = false) String time,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "10") int size) {
        return devices.history(deviceId, action, status, time, page, size);
    }
}
