package com.tickit.repository;

import com.tickit.model.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface UserRepository extends JpaRepository<AppUser, Long> {
    Optional<AppUser> findByAuthIssuerAndAuthSubject(String issuer, String subject);
    Optional<AppUser> findByEmail(String email);
}
