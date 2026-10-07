package com.ptit.iot.repository;

import com.ptit.iot.domain.SensorData;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

public interface SensorDataRepository extends JpaRepository<SensorData, Long>, JpaSpecificationExecutor<SensorData> {
    Optional<SensorData> findFirstBySensor_NameOrderByCreatedAtDescIdDesc(String sensorName);

    List<SensorData> findBySensor_NameOrderByCreatedAtDescIdDesc(String sensorName, Pageable pageable);
}
