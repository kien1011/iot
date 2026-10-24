package vn.ptit.iot.dto;
import vn.ptit.iot.entity.DeviceState;
public record ControlRequest(DeviceState action) {}
