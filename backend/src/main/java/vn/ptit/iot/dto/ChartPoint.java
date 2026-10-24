package vn.ptit.iot.dto;
import java.time.LocalDateTime;
import vn.ptit.iot.entity.SensorData;
public record ChartPoint(Long id, LocalDateTime time, double value) {
    public static ChartPoint from(SensorData row) {
        return new ChartPoint(row.getId(), row.getCreatedAt(), row.getValue());
    }
}
