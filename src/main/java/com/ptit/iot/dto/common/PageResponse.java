package com.ptit.iot.dto.common;

import org.springframework.data.domain.Page;

import java.util.List;

public record PageResponse<T>(
        List<T> content,
        long totalElements,
        int totalPages,
        int page,
        int size
) {
    public static <T> PageResponse<T> from(Page<T> pageData) {
        return new PageResponse<>(
                pageData.getContent(),
                pageData.getTotalElements(),
                Math.max(1, pageData.getTotalPages()),
                pageData.getNumber(),
                pageData.getSize()
        );
    }
}
