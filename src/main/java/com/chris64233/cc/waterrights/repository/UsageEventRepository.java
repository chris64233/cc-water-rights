package com.chris64233.cc.waterrights.repository;

import com.chris64233.cc.waterrights.domain.UsageEvent;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UsageEventRepository extends JpaRepository<UsageEvent, Long> {

    Optional<UsageEvent> findByExternalEventNo(String externalEventNo);

    List<UsageEvent> findByPermit_IdOrderByIdAsc(Long permitId);
}
