package vn.ptit.iot.dto;
import java.time.LocalDateTime;
public record SensorHistoryResponse(Long id, Integer sensorId, String sensorName, double value, LocalDateTime createdAt) {}
