package vn.ptit.iot.dto;
import vn.ptit.iot.entity.ActionStatus;
public record ControlResponse(Long id, ActionStatus status, int timeoutSeconds) {}
