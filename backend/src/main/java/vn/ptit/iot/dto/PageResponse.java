package vn.ptit.iot.dto;
import java.util.List;
import org.springframework.data.domain.Page;
public record PageResponse<T>(List<T> content, long totalElements, int totalPages, int page, int size) {
    public static <T> PageResponse<T> from(Page<T> result) {
        return new PageResponse<>(result.getContent(), result.getTotalElements(), result.getTotalPages(),
                result.getNumber(), result.getSize());
    }
}
