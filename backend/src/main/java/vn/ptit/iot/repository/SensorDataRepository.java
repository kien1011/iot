package vn.ptit.iot.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.*;
import vn.ptit.iot.entity.SensorData;

public interface SensorDataRepository extends JpaRepository<SensorData, Long>, JpaSpecificationExecutor<SensorData> {
    Optional<SensorData> findFirstBySensorIdOrderByIdDesc(Integer sensorId);
    List<SensorData> findTop20BySensorIdOrderByIdDesc(Integer sensorId);
    @Override @EntityGraph(attributePaths = "sensor")
    Page<SensorData> findAll(Specification<SensorData> specification, Pageable pageable);
}
