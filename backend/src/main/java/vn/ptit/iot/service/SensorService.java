package vn.ptit.iot.service;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import vn.ptit.iot.dto.*;
import vn.ptit.iot.entity.SensorData;
import vn.ptit.iot.repository.*;
import vn.ptit.iot.util.*;
import vn.ptit.iot.websocket.UpdatesSocket;
@Service
public class SensorService {
    private final SensorRepository sensors;
    private final SensorDataRepository data;
    private final TransactionTemplate tx;
    private final LuxConverter lux;
    private final Clock clock;
    private final UpdatesSocket updates;
    public SensorService(SensorRepository sensors, SensorDataRepository data, TransactionTemplate tx,
                         LuxConverter lux, Clock clock, UpdatesSocket updates) {
        this.sensors=sensors; this.data=data; this.tx=tx; this.lux=lux; this.clock=clock; this.updates=updates;
    }
    public void record(double temperature, double humidity, int adc) {
        LocalDateTime time = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
        double t = LuxConverter.roundOneDecimal(temperature), h = LuxConverter.roundOneDecimal(humidity), l = lux.convert(adc);
        SensorSnapshot snapshot = tx.execute(status -> {
            var rows = data.saveAll(List.of(
                new SensorData(sensors.getReferenceById(1), t, time),
                new SensorData(sensors.getReferenceById(2), h, time),
                new SensorData(sensors.getReferenceById(3), l, time)));
            return new SensorSnapshot(t, h, l, time, Map.of(
                "temperature", rows.get(0).getId(), "humidity", rows.get(1).getId(), "light", rows.get(2).getId()));
        });
        // execute has returned: the transaction, not only flush, has committed.
        updates.broadcast("sensor:update", snapshot);
    }
    public SensorSnapshot latest() {
        return tx.execute(status -> {
            var t = data.findFirstBySensorIdOrderByCreatedAtDescIdDesc(1).orElse(null);
            var h = data.findFirstBySensorIdOrderByCreatedAtDescIdDesc(2).orElse(null);
            var l = data.findFirstBySensorIdOrderByCreatedAtDescIdDesc(3).orElse(null);
            Map<String, Long> ids = new HashMap<>();
            if (t != null) ids.put("temperature", t.getId());
            if (h != null) ids.put("humidity", h.getId());
            if (l != null) ids.put("light", l.getId());
            return new SensorSnapshot(t == null ? null : t.getValue(), h == null ? null : h.getValue(),
                l == null ? null : l.getValue(), t == null ? null : t.getCreatedAt(), ids);
        });
    }
    public ChartResponse chart() { return tx.execute(status -> new ChartResponse(points(1), points(2), points(3))); }
    private List<ChartPoint> points(int sensorId) {
        var rows = data.findBySensorIdOrderByCreatedAtDescIdDesc(sensorId, PageRequest.of(0,20));
        var points = new ArrayList<ChartPoint>();
        for (int i=rows.size()-1; i>=0; i--) points.add(new ChartPoint(rows.get(i).getId(), rows.get(i).getCreatedAt(), rows.get(i).getValue()));
        return points;
    }
    public PageResponse<SensorHistoryResponse> history(Integer sensorId, String value, String time, int page, int size) {
        TimeRange range = TimeRange.parse(time);
        Double number = TimeRange.exactNumber(value);
        return tx.execute(status -> PageResponse.from(data.search(sensorId, number, range.start(), range.end(),
            PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))))
            .map(r -> new SensorHistoryResponse(r.getId(), r.getSensor().getId(), r.getSensor().getName(), r.getValue(), r.getCreatedAt()))));
    }
}
