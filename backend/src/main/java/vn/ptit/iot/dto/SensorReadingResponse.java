package vn.ptit.iot.dto;
import java.time.LocalDateTime;
public record SensorReadingResponse(Double temperature, Double humidity, Double light,
                                    LocalDateTime time, SensorIds ids) {}
