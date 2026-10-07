package com.ptit.iot.service;

import com.ptit.iot.domain.ActionHistory;
import com.ptit.iot.domain.enums.ActionStatus;
import com.ptit.iot.domain.enums.DeviceAction;
import com.ptit.iot.dto.common.PageResponse;
import com.ptit.iot.dto.device.ActionHistoryItemResponse;
import com.ptit.iot.exception.BadRequestException;
import com.ptit.iot.repository.ActionHistoryRepository;
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
import java.util.List;

@Service
@Transactional(readOnly = true)
public class ActionHistoryQueryService {
    private final ActionHistoryRepository actionHistoryRepository;
    private final TimeRangeParser timeRangeParser;

    public ActionHistoryQueryService(ActionHistoryRepository actionHistoryRepository,
                                     TimeRangeParser timeRangeParser) {
        this.actionHistoryRepository = actionHistoryRepository;
        this.timeRangeParser = timeRangeParser;
    }

    public PageResponse<ActionHistoryItemResponse> getHistory(
            Integer deviceId,
            String deviceName,
            DeviceAction action,
            ActionStatus status,
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

        Specification<ActionHistory> specification = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (deviceId != null) predicates.add(cb.equal(root.get("device").get("id"), deviceId));
            if (hasText(deviceName)) predicates.add(cb.equal(root.get("device").get("name"), deviceName.trim()));
            if (action != null) predicates.add(cb.equal(root.get("action"), action));
            if (status != null) predicates.add(cb.equal(root.get("status"), status));
            if (from != null) predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            if (to != null) predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), to));
            if (prefixRange != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), prefixRange.startInclusive()));
                predicates.add(cb.lessThan(root.get("createdAt"), prefixRange.endExclusive()));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };

        Pageable pageable = PageRequest.of(page, size, actionSort(sort));
        Page<ActionHistoryItemResponse> result = actionHistoryRepository.findAll(specification, pageable)
                .map(this::toHistoryItem);
        return PageResponse.from(result);
    }

    private ActionHistoryItemResponse toHistoryItem(ActionHistory row) {
        return new ActionHistoryItemResponse(
                row.getId(),
                row.getDevice().getId(),
                row.getDevice().getName(),
                row.getAction(),
                row.getStatus(),
                row.getUser().getId(),
                row.getUser().getUsername(),
                row.getCreatedAt()
        );
    }

    private Sort actionSort(String sort) {
        if (!hasText(sort)) {
            return Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
        }

        String[] parts = sort.split(",", 2);
        String requested = parts[0].trim();
        Sort.Direction direction = parts.length == 2 && "asc".equalsIgnoreCase(parts[1].trim())
                ? Sort.Direction.ASC
                : Sort.Direction.DESC;

        String property = switch (requested) {
            case "id" -> "id";
            case "createdAt", "time" -> "createdAt";
            case "action" -> "action";
            case "status" -> "status";
            case "deviceName" -> "device.name";
            case "username" -> "user.username";
            default -> throw new BadRequestException("Unsupported device history sort field.");
        };
        if ("createdAt".equals(property)) {
            return Sort.by(new Sort.Order(direction, property), Sort.Order.desc("id"));
        }
        return Sort.by(new Sort.Order(direction, property), Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
    }

    private void validatePage(int page, int size) {
        if (page < 0) throw new BadRequestException("page must be 0 or greater.");
        if (size < 1 || size > 100) throw new BadRequestException("size must be between 1 and 100.");
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
