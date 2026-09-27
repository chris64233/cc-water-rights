package com.chris64233.cc.waterrights.permit;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PermitRepository extends JpaRepository<Permit, Long> {

    Optional<Permit> findByPermitNo(String permitNo);

    /** 申报 / 冲正时锁定许可行，串行化对剩余额度的并发争抢。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Permit p where p.permitNo = :permitNo")
    Optional<Permit> findByPermitNoForUpdate(@Param("permitNo") String permitNo);

    boolean existsByPermitNo(String permitNo);
}
