package vn.ptit.iot.repository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import vn.ptit.iot.entity.*;
public interface ActionHistoryRepository extends JpaRepository<ActionHistory, Long> {
    Optional<ActionHistory> findFirstByDeviceIdAndStatusNotOrderByCreatedAtDescIdDesc(Integer deviceId, ActionStatus status);
    @EntityGraph(attributePaths={"user", "device"})
    @Query("""
        select a from ActionHistory a where (:deviceId is null or a.device.id = :deviceId)
        and (:action is null or a.action = :action) and (:status is null or a.status = :status)
        and (:start is null or a.createdAt >= :start) and (:end is null or a.createdAt < :end)
        """)
    Page<ActionHistory> search(Integer deviceId, DeviceStatus action, ActionStatus status,
                              LocalDateTime start, LocalDateTime end, Pageable pageable);
}
