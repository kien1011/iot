package com.ptit.iot.controller;

import com.ptit.iot.domain.enums.ActionStatus;
import com.ptit.iot.domain.enums.DeviceAction;
import com.ptit.iot.dto.common.PageResponse;
import com.ptit.iot.dto.device.ActionHistoryItemResponse;
import com.ptit.iot.dto.device.DeviceControlRequest;
import com.ptit.iot.dto.device.DeviceControlResponse;
import com.ptit.iot.dto.device.DeviceStatusResponse;
import com.ptit.iot.service.ActionHistoryQueryService;
import com.ptit.iot.service.DeviceControlService;
import com.ptit.iot.service.DeviceQueryService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/devices")
public class DeviceController {
    private final DeviceQueryService deviceQueryService;
    private final DeviceControlService deviceControlService;
    private final ActionHistoryQueryService actionHistoryQueryService;

    public DeviceController(DeviceQueryService deviceQueryService,
                            DeviceControlService deviceControlService,
                            ActionHistoryQueryService actionHistoryQueryService) {
        this.deviceQueryService = deviceQueryService;
        this.deviceControlService = deviceControlService;
        this.actionHistoryQueryService = actionHistoryQueryService;
    }

    @GetMapping("/{deviceId}/status")
    public DeviceStatusResponse status(@PathVariable Integer deviceId) {
        return deviceQueryService.getStatus(deviceId);
    }

    @PostMapping("/{deviceId}/control")
    public DeviceControlResponse control(@PathVariable Integer deviceId,
                                         @Valid @RequestBody DeviceControlRequest body,
                                         Authentication authentication) {
        return deviceControlService.control(deviceId, body.action(), authentication.getName());
    }

    @GetMapping("/action-history")
    public PageResponse<ActionHistoryItemResponse> actionHistory(
            @RequestParam(required = false) Integer deviceId,
            @RequestParam(required = false) String deviceName,
            @RequestParam(required = false) DeviceAction action,
            @RequestParam(required = false) ActionStatus status,
            @RequestParam(required = false) String time,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String sort
    ) {
        return actionHistoryQueryService.getHistory(
                deviceId, deviceName, action, status, time, from, to, page, size, sort
        );
    }
}
