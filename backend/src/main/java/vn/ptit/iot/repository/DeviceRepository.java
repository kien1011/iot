package vn.ptit.iot.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.ptit.iot.entity.Device;

public interface DeviceRepository extends JpaRepository<Device, Integer> {}
