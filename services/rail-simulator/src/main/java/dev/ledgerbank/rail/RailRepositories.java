package dev.ledgerbank.rail;
import java.time.Instant;import java.util.*;import org.springframework.data.jpa.repository.JpaRepository;
interface ExternalAccountRepository extends JpaRepository<ExternalAccount,UUID>{List<ExternalAccount>findAllByUserSubjectOrderByCreatedAt(UUID user);Optional<ExternalAccount>findByIdAndUserSubject(UUID id,UUID user);}
interface AchTransferRepository extends JpaRepository<AchTransfer,UUID>{Optional<AchTransfer>findByUserSubjectAndIdempotencyKey(UUID user,String key);Optional<AchTransfer>findByIdAndUserSubject(UUID id,UUID user);List<AchTransfer>findAllByUserSubjectOrderByCreatedAtDesc(UUID user);List<AchTransfer>findTop50ByNextTransitionAtBeforeAndStatusInOrderByNextTransitionAt(Instant due,Collection<String>statuses);}

