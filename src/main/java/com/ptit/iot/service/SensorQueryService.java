package com.ptit.iot.service;

import com.ptit.iot.domain.SensorData;
import com.ptit.iot.dto.common.PageResponse;
import com.ptit.iot.dto.sensor.SensorChartResponse;
import com.ptit.iot.dto.sensor.SensorHistoryItemResponse;
import com.ptit.iot.dto.sensor.SensorLatestResponse;
import com.ptit.iot.dto.sensor.SensorPointResponse;
import com.ptit.iot.exception.BadRequestException;
import com.ptit.iot.repository.SensorDataRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Service
@Transactional(readOnly = true)
public class SensorQueryService {
    private final SensorDataRepository sensorDataRepository;
    private final TimeRangeParser timeRangeParser;

    public SensorQueryService(SensorDataRepository sensorDataRepository,
                              TimeRangeParser timeRangeParser) {
        this.sensorDataRepository = sensorDataRepository;
        this.timeRangeParser = timeRangeParser;
    }

    public Optional<SensorLatestResponse> getLatest() {
        Optional<SensorData> temperature = sensorDataRepository.findFirstBySensor_NameOrderByCreatedAtDescIdDesc("Temperature");
        Optional<SensorData> humidity = sensorDataRepository.findFirstBySensor_NameOrderByCreatedAtDescIdDesc("Humidity");
        Optional<SensorData> light = sensorDataRepository.findFirstBySensor_NameOrderByCreatedAtDescIdDesc("Light");

        if (temperature.isEmpty() || humidity.isEmpty() || light.isEmpty()) {
            return Optional.empty();
        }

        LocalDateTime time = max(
                temperature.get().getCreatedAt(),
                humidity.get().getCreatedAt(),
                light.get().getCreatedAt()
        );

        return Optional.of(new SensorLatestResponse(
                temperature.get().getValue(),
                humidity.get().getValue(),
                light.get().getValue(),
                time
        ));
    }

    public SensorChartResponse getChart(int limit) {
        if (limit < 1 || limit > 100) {
            throw new BadRequestException("limit must be between 1 and 100.");
        }

        return new SensorChartResponse(
                pointsFor("Temperature", limit),
                pointsFor("Humidity", limit),
                pointsFor("Light", limit)
        );
    }

    public PageResponse<SensorHistoryItemResponse> getHistory(
            Integer sensorId,
            String sensorName,
            String value,
            String time,
            LocalDateTime from,
            LocalDateTime to,
            int page,
            int size,
            String sort
    ) {
        validatePage(page, size);
        if (from != null && to != null && from.isAfter(to)) {
            throw new BadRequestException("from must be before or equal to to.");
        }

        TimeRangeParser.TimeRange prefixRange = hasText(time) ? timeRangeParser.parse(time) : null;
        Double exactValue = hasText(value) ? ExactSensorValueParser.parse(value) : null;

        Specification<SensorData> specification = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (sensorId != null) predicates.add(cb.equal(root.get("sensor").get("id"), sensorId));
            if (hasText(sensorName)) predicates.add(cb.equal(root.get("sensor").get("name"), sensorName.trim()));
            if (exactValue != null) predicates.add(cb.equal(root.get("value"), exactValue));
            if (from != null) predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            if (to != null) predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), to));
            if (prefixRange != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), prefixRange.startInclusive()));
                predicates.add(cb.lessThan(root.get("createdAt"), prefixRange.endExclusive()));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };

        Pageable pageable = PageRequest.of(page, size, sensorSort(sort));
        Page<SensorHistoryItemResponse> result = sensorDataRepository.findAll(specification, pageable)
                .map(this::toHistoryItem);
        return PageResponse.from(result);
    }

    private List<SensorPointResponse> pointsFor(String sensorName, int limit) {
        List<SensorData> rows = new ArrayList<>(sensorDataRepository.findBySensor_NameOrderByCreatedAtDescIdDesc(
                sensorName,
                PageRequest.of(0, limit)
        ));
        Collections.reverse(rows);
        return rows.stream()
                .map(row -> new SensorPointResponse(row.getCreatedAt(), row.getValue()))
                .toList();
    }

    private SensorHistoryItemResponse toHistoryItem(SensorData row) {
        return new SensorHistoryItemResponse(
                row.getId(),
                row.getSensor().getId(),
                row.getSensor().getName(),
                row.getValue(),
                row.getCreatedAt()
        );
    }

    private Sort sensorSort(String sort) {
        if (!hasText(sort)) {
            // Newest measurement first; within the same measurement: Temperature, Humidity, Light insertion order.
            return Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id"));
        }

        String[] parts = sort.split(",", 2);
        String requested = parts[0].trim();
        Sort.Direction direction = parts.length == 2 && "asc".equalsIgnoreCase(parts[1].trim())
                ? Sort.Direction.ASC
                : Sort.Direction.DESC;

        String property = switch (requested) {
            case "id" -> "id";
            case "value" -> "value";
            case "createdAt", "time" -> "createdAt";
            case "sensorName" -> "sensor.name";
            default -> throw new BadRequestException("Unsupported sensor history sort field.");
        };
        if ("createdAt".equals(property)) {
            return Sort.by(new Sort.Order(direction, property), Sort.Order.asc("id"));
        }
        return Sort.by(new Sort.Order(direction, property), Sort.Order.desc("createdAt"), Sort.Order.asc("id"));
    }

    private void validatePage(int page, int size) {
        if (page < 0) throw new BadRequestException("page must be 0 or greater.");
        if (size < 1 || size > 100) throw new BadRequestException("size must be between 1 and 100.");
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private LocalDateTime max(LocalDateTime a, LocalDateTime b, LocalDateTime c) {
        LocalDateTime result = a.isAfter(b) ? a : b;
        return result.isAfter(c) ? result : c;
    }
}
