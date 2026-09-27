package com.chris64233.cc.waterrights.permit;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WaterEventRepository extends JpaRepository<WaterEvent, Long> {

    Optional<WaterEvent> findByEventNo(String eventNo);

    Optional<WaterEvent> findByReversesEvent_Id(Long reversesEventId);

    List<WaterEvent> findByPermit_IdOrderByIdAsc(Long permitId);
}
