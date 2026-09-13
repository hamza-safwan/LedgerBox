package dev.ledgerbank.customer;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

interface CustomerRepository extends JpaRepository<CustomerProfile, UUID> {
    Optional<CustomerProfile> findByUserSubject(UUID subject);
}

