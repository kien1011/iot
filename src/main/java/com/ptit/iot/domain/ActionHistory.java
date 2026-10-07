package com.ptit.iot.domain;

import com.ptit.iot.domain.enums.ActionStatus;
import com.ptit.iot.domain.enums.DeviceAction;
import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "action_history")
public class ActionHistory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "device_id", nullable = false)
    private Device device;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "ENUM('ON','OFF')")
    private DeviceAction action;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "ENUM('ON','OFF','LOADING')")
    private ActionStatus status = ActionStatus.LOADING;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (status == null) status = ActionStatus.LOADING;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public AppUser getUser() { return user; }
    public void setUser(AppUser user) { this.user = user; }
    public Device getDevice() { return device; }
    public void setDevice(Device device) { this.device = device; }
    public DeviceAction getAction() { return action; }
    public void setAction(DeviceAction action) { this.action = action; }
    public ActionStatus getStatus() { return status; }
    public void setStatus(ActionStatus status) { this.status = status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
