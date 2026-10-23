package com.tickit.repository;

import com.tickit.model.AdmissionHold;
import java.util.UUID;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface AdmissionHoldRepository extends JpaRepository<AdmissionHold, UUID> {
  @Query(
      value =
          "SELECT COALESCE(SUM(h.quantity),0) FROM admission_hold h JOIN admission_ticket_type t ON"
              + " t.id=h.ticket_type_id WHERE t.pool_id=:pool AND NOT h.released AND"
              + " h.expires_at>clock_timestamp()",
      nativeQuery = true)
  long activeInPool(@Param("pool") Long pool);

  @Query(
      value =
          "SELECT COALESCE(SUM(quantity),0) FROM admission_hold WHERE ticket_type_id=:ticket AND"
              + " NOT released AND expires_at>clock_timestamp()",
      nativeQuery = true)
  long activeForTicket(@Param("ticket") Long ticket);

  @Query(
      value =
          "SELECT COALESCE(SUM(quantity),0) FROM admission_hold WHERE user_id=:user AND"
              + " event_id=:event AND NOT released AND expires_at>clock_timestamp()",
      nativeQuery = true)
  long activeForUser(@Param("user") Long user, @Param("event") Long event);

  @Modifying
  @Query(
      value = "DELETE FROM admission_hold WHERE expires_at < now() - interval '1 day'",
      nativeQuery = true)
  int pruneExpired();
}
