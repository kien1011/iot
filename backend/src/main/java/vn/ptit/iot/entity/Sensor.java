package vn.ptit.iot.entity;
import jakarta.persistence.*;
import java.time.LocalDateTime;
@Entity
@Table(name="sensors")
public class Sensor {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Integer id;
    @Column(nullable=false, length=100) private String name;
    @Column(name="created_at", nullable=false, updatable=false, columnDefinition="DATETIME")
    private LocalDateTime createdAt;
    protected Sensor() {}

    public Integer getId() { return id; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public String getName() { return name; }
}
