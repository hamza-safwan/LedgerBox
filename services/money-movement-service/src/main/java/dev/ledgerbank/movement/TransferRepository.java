package dev.ledgerbank.movement;
import java.time.Instant;import java.util.*;import org.springframework.data.jpa.repository.JpaRepository;
interface TransferRepository extends JpaRepository<Transfer,UUID>{Optional<Transfer>findByUserSubjectAndIdempotencyKey(UUID user,String key);Optional<Transfer>findByIdAndUserSubject(UUID id,UUID user);List<Transfer>findAllByUserSubjectOrderByCreatedAtDesc(UUID user);List<Transfer>findTop50ByStatusAndUpdatedAtBeforeOrderByUpdatedAt(String status,Instant before);}

