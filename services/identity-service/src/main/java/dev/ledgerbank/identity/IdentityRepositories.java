package dev.ledgerbank.identity;

import jakarta.persistence.LockModeType;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

interface UserAccountRepository extends JpaRepository<UserAccount, UUID> {
    Optional<UserAccount> findByEmail(String email);
}

interface ActionTokenRepository extends JpaRepository<ActionToken, UUID> {
    Optional<ActionToken> findByTokenHashAndPurpose(String tokenHash, String purpose);
}

interface RefreshSessionRepository extends JpaRepository<RefreshSession, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RefreshSession r where r.tokenHash = :hash")
    Optional<RefreshSession> findLockedByHash(@Param("hash") String hash);
    List<RefreshSession> findAllByFamilyId(UUID familyId);
}

