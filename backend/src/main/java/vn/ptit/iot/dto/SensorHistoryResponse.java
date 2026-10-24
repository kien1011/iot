package vn.ptit.iot.dto;
import java.time.LocalDateTime;
import vn.ptit.iot.entity.SensorData;
public record SensorHistoryResponse(Long id, Integer sensorId, String sensorName, double value, LocalDateTime createdAt) {
    public static SensorHistoryResponse from(SensorData row) {
        return new SensorHistoryResponse(row.getId(), row.getSensor().getId(), row.getSensor().getName(), row.getValue(), row.getCreatedAt());
    }
}
