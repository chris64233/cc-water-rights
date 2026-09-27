package com.chris64233.cc.waterrights.repository;

import com.chris64233.cc.waterrights.domain.WaterPermit;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WaterPermitRepository extends JpaRepository<WaterPermit, Long> {

    Optional<WaterPermit> findByPermitNo(String permitNo);

    boolean existsByPermitNo(String permitNo);

    /**
     * 按许可号加行级悲观写锁加载。申报/冲正事务内使用，
     * 串行化同一许可上的额度变更，保证并发申报不会超额。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from WaterPermit p where p.permitNo = :permitNo")
    Optional<WaterPermit> findByPermitNoForUpdate(@Param("permitNo") String permitNo);
}
