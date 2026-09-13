package dev.ledgerbank.identity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "user_accounts")
class UserAccount {
    @Id UUID id;
    @Column(nullable = false, unique = true) String email;
    @Column(name = "password_hash", nullable = false) String passwordHash;
    @Column(nullable = false) String role;
    @Column(nullable = false) boolean enabled;
    @Column(name = "failed_attempts", nullable = false) int failedAttempts;
    @Column(name = "locked_until") Instant lockedUntil;
    @Column(name = "created_at", nullable = false) Instant createdAt;
    protected UserAccount() {}
    UserAccount(UUID id, String email, String passwordHash, String role, boolean enabled) {
        this.id = id; this.email = email; this.passwordHash = passwordHash; this.role = role;
        this.enabled = enabled; this.createdAt = Instant.now();
    }
}

@Entity
@Table(name = "action_tokens")
class ActionToken {
    @Id UUID id;
    @Column(name = "user_id", nullable = false) UUID userId;
    @Column(name = "token_hash", nullable = false, unique = true, length = 64) String tokenHash;
    @Column(nullable = false) String purpose;
    @Column(name = "expires_at", nullable = false) Instant expiresAt;
    @Column(name = "used_at") Instant usedAt;
    protected ActionToken() {}
    ActionToken(UUID userId, String tokenHash, String purpose, Instant expiresAt) {
        this.id = UUID.randomUUID(); this.userId = userId; this.tokenHash = tokenHash;
        this.purpose = purpose; this.expiresAt = expiresAt;
    }
}

@Entity
@Table(name = "refresh_sessions")
class RefreshSession {
    @Id UUID id;
    @Column(name = "user_id", nullable = false) UUID userId;
    @Column(name = "family_id", nullable = false) UUID familyId;
    @Column(name = "token_hash", nullable = false, unique = true, length = 64) String tokenHash;
    @Column(name = "expires_at", nullable = false) Instant expiresAt;
    @Column(name = "revoked_at") Instant revokedAt;
    @Column(name = "replaced_by") UUID replacedBy;
    @Column(name = "created_at", nullable = false) Instant createdAt;
    protected RefreshSession() {}
    RefreshSession(UUID userId, UUID familyId, String tokenHash) {
        this.id = UUID.randomUUID(); this.userId = userId; this.familyId = familyId;
        this.tokenHash = tokenHash; this.expiresAt = Instant.now().plusSeconds(30L * 24 * 3600);
        this.createdAt = Instant.now();
    }
}
