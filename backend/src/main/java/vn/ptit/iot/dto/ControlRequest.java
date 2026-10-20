package vn.ptit.iot.dto;
import vn.ptit.iot.entity.DeviceStatus;
public record ControlRequest(DeviceStatus action) {}
