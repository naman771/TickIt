package com.tickit.repository;

import com.tickit.model.AdmissionPool;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdmissionPoolRepository extends JpaRepository<AdmissionPool, Long> {
  List<AdmissionPool> findByEventIdOrderById(Long eventId);
}
