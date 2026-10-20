package vn.ptit.iot.repository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import vn.ptit.iot.entity.SensorData;
public interface SensorDataRepository extends JpaRepository<SensorData, Long> {
    Optional<SensorData> findFirstBySensorIdOrderByCreatedAtDescIdDesc(Integer sensorId);
    List<SensorData> findBySensorIdOrderByCreatedAtDescIdDesc(Integer sensorId, Pageable pageable);
    @EntityGraph(attributePaths="sensor")
    @Query("""
        select s from SensorData s where (:sensorId is null or s.sensor.id = :sensorId)
        and (:value is null or s.value = :value)
        and (:start is null or s.createdAt >= :start)
        and (:end is null or s.createdAt < :end)
        """)
    Page<SensorData> search(Integer sensorId, Double value, LocalDateTime start, LocalDateTime end, Pageable pageable);
}
