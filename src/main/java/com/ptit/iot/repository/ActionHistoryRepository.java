package com.ptit.iot.repository;

import com.ptit.iot.domain.ActionHistory;
import com.ptit.iot.domain.enums.ActionStatus;
import com.ptit.iot.domain.enums.DeviceAction;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ActionHistoryRepository extends JpaRepository<ActionHistory, Long>, JpaSpecificationExecutor<ActionHistory> {
    @Query("""
            select h from ActionHistory h
            where h.device.id = :deviceId
              and ((h.action = :onAction and h.status = :onStatus)
                or (h.action = :offAction and h.status = :offStatus))
            order by h.createdAt desc, h.id desc
            """)
    List<ActionHistory> findSuccessfulForDevice(
            @Param("deviceId") Integer deviceId,
            @Param("onAction") DeviceAction onAction,
            @Param("onStatus") ActionStatus onStatus,
            @Param("offAction") DeviceAction offAction,
            @Param("offStatus") ActionStatus offStatus,
            Pageable pageable
    );
}
