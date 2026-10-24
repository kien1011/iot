package vn.ptit.iot.service;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import vn.ptit.iot.dto.*;
import vn.ptit.iot.entity.SensorData;
import vn.ptit.iot.repository.*;
import vn.ptit.iot.util.*;
import vn.ptit.iot.websocket.LiveUpdates;

@Service
public class SensorService {
    private final SensorRepository sensors;
    private final SensorDataRepository data;
    private final LuxConverter lux;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final LiveUpdates live;

    public SensorService(SensorRepository sensors, SensorDataRepository data, LuxConverter lux,
                         Clock clock, TransactionTemplate transactions, LiveUpdates live) {
        this.sensors = sensors; this.data = data; this.lux = lux;
        this.clock = clock; this.transactions = transactions; this.live = live;
    }

    public void record(double temperature, double humidity, int rawAdc) {
        var reading = transactions.execute(tx -> {
            var time = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
            // Exactly one ADC conversion. All consumers read these persisted values.
            var rows = data.saveAll(List.of(
                new SensorData(sensors.getReferenceById(1), LuxConverter.roundOneDecimal(temperature), time),
                new SensorData(sensors.getReferenceById(2), LuxConverter.roundOneDecimal(humidity), time),
                new SensorData(sensors.getReferenceById(3), lux.convert(rawAdc), time)));
            return new SensorReadingResponse(rows.get(0).getValue(), rows.get(1).getValue(), rows.get(2).getValue(), time,
                new SensorIds(rows.get(0).getId(), rows.get(1).getId(), rows.get(2).getId()));
        });
        // execute() has returned: the transaction has really committed.
        live.broadcast("sensor:update", reading);
    }

    @Transactional(readOnly = true)
    public SensorReadingResponse latest() {
        var t = data.findFirstBySensorIdOrderByIdDesc(1).orElse(null);
        var h = data.findFirstBySensorIdOrderByIdDesc(2).orElse(null);
        var l = data.findFirstBySensorIdOrderByIdDesc(3).orElse(null);
        return new SensorReadingResponse(value(t), value(h), value(l), t == null ? null : t.getCreatedAt(),
            new SensorIds(id(t), id(h), id(l)));
    }

    private static Long id(SensorData row) { return row == null ? null : row.getId(); }
    private static Double value(SensorData row) { return row == null ? null : row.getValue(); }

    @Transactional(readOnly = true)
    public ChartResponse chart() { return new ChartResponse(points(1), points(2), points(3)); }

    private List<ChartPoint> points(int sensorId) {
        var rows = new ArrayList<>(data.findTop20BySensorIdOrderByIdDesc(sensorId));
        Collections.reverse(rows);
        return rows.stream().map(ChartPoint::from).toList();
    }

    @Transactional(readOnly = true)
    public PageResponse<SensorHistoryResponse> history(Integer sensorId, String valueText, String timeText, int page, int size) {
        Double value = ExactNumber.parse(valueText);
        var time = TimeRange.parse(timeText);
        Specification<SensorData> spec = (root, query, cb) -> {
            var conditions = new ArrayList<jakarta.persistence.criteria.Predicate>();
            if (sensorId != null) conditions.add(cb.equal(root.get("sensor").get("id"), sensorId));
            if (value != null) conditions.add(cb.equal(root.get("value"), value));
            if (time != null) {
                conditions.add(cb.greaterThanOrEqualTo(root.get("createdAt"), time.start()));
                // MySQL DATETIME ends at year 9999: year 10000 is an implicit storage bound.
                if (time.end().getYear() <= 9999) conditions.add(cb.lessThan(root.get("createdAt"), time.end()));
            }
            return cb.and(conditions.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
        return PageResponse.from(data.findAll(spec, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id")))
            .map(SensorHistoryResponse::from));
    }
}
