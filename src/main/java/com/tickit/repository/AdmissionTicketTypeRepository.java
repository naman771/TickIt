package com.tickit.repository;

import com.tickit.model.AdmissionTicketType;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdmissionTicketTypeRepository extends JpaRepository<AdmissionTicketType, Long> {
  List<AdmissionTicketType> findByPoolIdOrderById(Long poolId);
}
