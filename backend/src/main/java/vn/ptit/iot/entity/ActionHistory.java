package vn.ptit.iot.entity;
import jakarta.persistence.*;
import java.time.LocalDateTime;
@Entity
@Table(name="action_history")
public class ActionHistory {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;

    @ManyToOne(fetch=FetchType.LAZY, optional=false)
    @JoinColumn(name="user_id", nullable=false) private User user;
    @ManyToOne(fetch=FetchType.LAZY, optional=false)
    @JoinColumn(name="device_id", nullable=false) private Device device;
    @Enumerated(EnumType.STRING)
    @Column(nullable=false, columnDefinition="ENUM('ON','OFF')") private DeviceStatus action;
    @Enumerated(EnumType.STRING)
    @Column(nullable=false, columnDefinition="ENUM('ON','OFF','LOADING')") private ActionStatus status;

    @Column(name="created_at", nullable=false, updatable=false, columnDefinition="DATETIME")
    private LocalDateTime createdAt;
    protected ActionHistory() {}

    public ActionHistory(User user, Device device, DeviceStatus action, LocalDateTime createdAt) {
        this.user = user; this.device = device; this.action = action;
        this.status = ActionStatus.LOADING; this.createdAt = createdAt;
    } 

    public Long getId() { return id; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    public User getUser() { return user; }
    public Device getDevice() { return device; }
    public DeviceStatus getAction() { return action; }
    public ActionStatus getStatus() { return status; }
    public void confirm() { status = ActionStatus.valueOf(action.name()); }

}
