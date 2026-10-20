package vn.ptit.iot.dto;
import java.time.LocalDateTime;
public record ChartPoint(Long id, LocalDateTime time, double value) {}
