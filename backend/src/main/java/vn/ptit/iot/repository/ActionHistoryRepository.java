package vn.ptit.iot.repository;

import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.*;
import vn.ptit.iot.entity.ActionHistory;

public interface ActionHistoryRepository extends JpaRepository<ActionHistory, Long>, JpaSpecificationExecutor<ActionHistory> {
    @Override @EntityGraph(attributePaths = {"user", "device"})
    Page<ActionHistory> findAll(Specification<ActionHistory> specification, Pageable pageable);
}
