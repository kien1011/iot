package vn.ptit.iot.dto;
import java.time.LocalDateTime;
import vn.ptit.iot.entity.DeviceStatus;
import vn.ptit.iot.entity.ActionStatus;
public record ActionHistoryResponse(Long id, String deviceName, DeviceStatus action, ActionStatus status, Long userId, String username, LocalDateTime createdAt) {}
