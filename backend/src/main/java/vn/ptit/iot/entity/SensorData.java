package vn.ptit.iot.entity;
import jakarta.persistence.*;
import java.time.LocalDateTime;
@Entity
@Table(name="sensor_data")
public class SensorData {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;

    @ManyToOne(fetch=FetchType.LAZY, optional=false)
    @JoinColumn(name="sensor_id", nullable=false) private Sensor sensor;
    @Column(nullable=false) private double value;

    @Column(name="created_at", nullable=false, updatable=false, columnDefinition="DATETIME")
    private LocalDateTime createdAt;
    protected SensorData() {}

    public SensorData(Sensor sensor, double value, LocalDateTime createdAt) {
        this.sensor = sensor; this.value = value; this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    public Sensor getSensor() { return sensor; }
    public double getValue() { return value; }

}
