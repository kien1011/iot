package vn.ptit.iot.dto;
import java.time.LocalDateTime;
import vn.ptit.iot.entity.Device;
import vn.ptit.iot.entity.DeviceState;
public record DeviceStatusResponse(Integer deviceId, DeviceState status, LocalDateTime updatedAt) {
    public static DeviceStatusResponse from(Device device) {
        return new DeviceStatusResponse(device.getId(), device.getStatus(), device.getUpdatedAt());
    }
}
