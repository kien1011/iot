package vn.ptit.iot.dto;
import java.util.List;
public record ChartResponse(List<ChartPoint> temperature, List<ChartPoint> humidity, List<ChartPoint> light) {}
