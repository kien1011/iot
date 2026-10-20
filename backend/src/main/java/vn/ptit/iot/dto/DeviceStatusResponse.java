package vn.ptit.iot.dto;
import java.time.LocalDateTime;
import vn.ptit.iot.entity.DeviceStatus;
public record DeviceStatusResponse(Integer deviceId, DeviceStatus status, LocalDateTime updatedAt) {}
