package vn.ptit.iot.dto;
import java.time.LocalDateTime;
import vn.ptit.iot.entity.*;
public record ActionHistoryResponse(Long id, String deviceName, DeviceState action, ActionStatus status,
                                    Long userId, String username, LocalDateTime createdAt) {
    public static ActionHistoryResponse from(ActionHistory row) {
        return new ActionHistoryResponse(row.getId(), row.getDevice().getName(), row.getAction(), row.getStatus(),
                row.getUser().getId(), row.getUser().getUsername(), row.getCreatedAt());
    }
}
