package vn.ptit.iot.dto;
import java.time.LocalDateTime;
import java.util.Map;
// IDs are sensor_data primary keys, used only to merge REST and WS chart points.
public record SensorSnapshot(Double temperature, Double humidity, Double light,
                             LocalDateTime time, Map<String, Long> ids) {}
