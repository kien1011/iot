package vn.ptit.iot.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.ptit.iot.entity.Sensor;

public interface SensorRepository extends JpaRepository<Sensor, Integer> {}
