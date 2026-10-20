package vn.ptit.iot.entity;
import jakarta.persistence.*;
import java.time.LocalDateTime;
@Entity
@Table(name="devices")
public class Device {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Integer id;

    @Column(nullable=false, length=100) private String name;
    @Enumerated(EnumType.STRING)
    @Column(nullable=false, columnDefinition="ENUM('ON','OFF')") private DeviceStatus status;

    @Column(name="created_at", nullable=false, updatable=false, columnDefinition="DATETIME")
    private LocalDateTime createdAt;
    protected Device() {}

    public Integer getId() { return id; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    public String getName() { return name; }
    public DeviceStatus getStatus() { return status; }
    public void confirm(DeviceStatus status) { this.status = status; }

}
