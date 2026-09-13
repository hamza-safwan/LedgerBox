package dev.ledgerbank.identity;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AuthService {
    private static final Duration ACCESS_TTL = Duration.ofMinutes(10);
    private final UserAccountRepository users;
    private final ActionTokenRepository actionTokens;
    private final RefreshSessionRepository sessions;
    private final PasswordEncoder passwords;
    private final JwtEncoder jwtEncoder;
    private final JwtKeyProvider keys;
    private final JavaMailSender mail;
    private final JdbcTemplate jdbc;
    private final SecureRandom random = new SecureRandom();
    private final String webBaseUrl;
    private final String serviceClientId;
    private final String serviceClientSecret;

    AuthService(UserAccountRepository users, ActionTokenRepository actionTokens,
                RefreshSessionRepository sessions, PasswordEncoder passwords,
                JwtEncoder jwtEncoder, JwtKeyProvider keys, JavaMailSender mail, JdbcTemplate jdbc,
                @Value("${ledgerbank.web-base-url}") String webBaseUrl,
                @Value("${ledgerbank.service-client-id}") String serviceClientId,
                @Value("${ledgerbank.service-client-secret}") String serviceClientSecret) {
        this.users = users; this.actionTokens = actionTokens; this.sessions = sessions;
        this.passwords = passwords; this.jwtEncoder = jwtEncoder; this.keys = keys;
        this.mail = mail; this.jdbc = jdbc; this.webBaseUrl = webBaseUrl;
        this.serviceClientId = serviceClientId; this.serviceClientSecret = serviceClientSecret;
    }

    @Transactional
    UUID register(String email, String password, String correlationId) {
        String normalized = normalizeEmail(email);
        if (users.findByEmail(normalized).isPresent()) throw new ApiException(409, "email_unavailable", "Email is unavailable");
        UserAccount user = users.save(new UserAccount(UUID.randomUUID(), normalized, passwords.encode(password), "CUSTOMER", false));
        String raw = randomToken();
        actionTokens.save(new ActionToken(user.id, sha256(raw), "VERIFY_EMAIL", Instant.now().plus(Duration.ofHours(24))));
        send(normalized, "Verify your LedgerBank sandbox account", webBaseUrl + "/verify-email?token=" + raw);
        audit(user.id, "REGISTER", "SUCCESS", correlationId);
        return user.id;
    }

    @Transactional
    void verifyEmail(String rawToken, String correlationId) {
        ActionToken token = actionTokens.findByTokenHashAndPurpose(sha256(rawToken), "VERIFY_EMAIL")
                .orElseThrow(() -> new ApiException(400, "invalid_token", "Verification token is invalid"));
        if (token.usedAt != null || token.expiresAt.isBefore(Instant.now())) throw new ApiException(400, "expired_token", "Verification token is expired");
        UserAccount user = users.findById(token.userId).orElseThrow();
        user.enabled = true; token.usedAt = Instant.now();
        audit(user.id, "VERIFY_EMAIL", "SUCCESS", correlationId);
    }

    @Transactional
    SessionTokens login(String email, String password, String correlationId) {
        UserAccount user = users.findByEmail(normalizeEmail(email)).orElse(null);
        if (user == null) throw invalidCredentials(correlationId);
        Instant now = Instant.now();
        if (user.lockedUntil != null && user.lockedUntil.isAfter(now)) {
            audit(user.id, "LOGIN", "LOCKED", correlationId);
            throw new ApiException(429, "temporarily_locked", "Sign-in is temporarily unavailable");
        }
        if (!passwords.matches(password, user.passwordHash)) {
            user.failedAttempts++;
            if (user.failedAttempts >= 5) { user.lockedUntil = now.plus(Duration.ofMinutes(15)); user.failedAttempts = 0; }
            audit(user.id, "LOGIN", "FAILURE", correlationId);
            throw invalidCredentials(correlationId);
        }
        if (!user.enabled) throw new ApiException(403, "email_not_verified", "Verify your email before signing in");
        user.failedAttempts = 0; user.lockedUntil = null;
        SessionTokens result = issueSession(user, UUID.randomUUID());
        audit(user.id, "LOGIN", "SUCCESS", correlationId);
        return result;
    }

    @Transactional
    SessionTokens refresh(String rawRefresh, String correlationId) {
        RefreshSession old = sessions.findLockedByHash(sha256(rawRefresh))
                .orElseThrow(() -> new ApiException(401, "invalid_session", "Session is invalid"));
        if (old.revokedAt != null) {
            revokeFamily(old.familyId);
            audit(old.userId, "REFRESH_REUSE", "REVOKED_FAMILY", correlationId);
            throw new ApiException(401, "session_reuse", "Session has been revoked");
        }
        if (old.expiresAt.isBefore(Instant.now())) throw new ApiException(401, "expired_session", "Session is expired");
        UserAccount user = users.findById(old.userId).orElseThrow();
        old.revokedAt = Instant.now();
        SessionTokens replacement = issueSession(user, old.familyId);
        old.replacedBy = replacement.sessionId();
        audit(user.id, "REFRESH", "SUCCESS", correlationId);
        return replacement;
    }

    @Transactional
    void logout(String rawRefresh, String correlationId) {
        if (rawRefresh == null) return;
        sessions.findLockedByHash(sha256(rawRefresh)).ifPresent(session -> {
            session.revokedAt = Instant.now();
            audit(session.userId, "LOGOUT", "SUCCESS", correlationId);
        });
    }

    @Transactional
    void requestPasswordReset(String email) {
        users.findByEmail(normalizeEmail(email)).ifPresent(user -> {
            String raw = randomToken();
            actionTokens.save(new ActionToken(user.id, sha256(raw), "RESET_PASSWORD", Instant.now().plus(Duration.ofMinutes(30))));
            send(user.email, "Reset your LedgerBank sandbox password", webBaseUrl + "/reset-password?token=" + raw);
        });
    }

    @Transactional
    void confirmPasswordReset(String rawToken, String newPassword) {
        ActionToken token = actionTokens.findByTokenHashAndPurpose(sha256(rawToken), "RESET_PASSWORD")
                .orElseThrow(() -> new ApiException(400, "invalid_token", "Reset token is invalid"));
        if (token.usedAt != null || token.expiresAt.isBefore(Instant.now())) throw new ApiException(400, "expired_token", "Reset token is expired");
        UserAccount user = users.findById(token.userId).orElseThrow();
        user.passwordHash = passwords.encode(newPassword); token.usedAt = Instant.now();
        sessions.findAll().stream().filter(s -> s.userId.equals(user.id)).forEach(s -> s.revokedAt = Instant.now());
    }

    String serviceToken(String clientId, String clientSecret) {
        if (!Set.of(serviceClientId, "rail-simulator").contains(clientId) ||
            !MessageDigest.isEqual(serviceClientSecret.getBytes(StandardCharsets.UTF_8), clientSecret.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(401, "invalid_client", "Service credentials are invalid");
        }
        return encode(clientId, "SERVICE", "ledger.post ledger.read", Duration.ofMinutes(5), List.of("ledgerbank-internal"));
    }

    private SessionTokens issueSession(UserAccount user, UUID familyId) {
        String rawRefresh = randomToken();
        RefreshSession stored = sessions.save(new RefreshSession(user.id, familyId, sha256(rawRefresh)));
        String access = encode(user.id.toString(), user.role, user.role.equals("OPERATOR") ? "bank.read bank.write ops" : "bank.read bank.write",
                ACCESS_TTL, List.of("ledgerbank-api"));
        return new SessionTokens(access, rawRefresh, stored.id, user.id, user.email, user.role, ACCESS_TTL.toSeconds());
    }

    private String encode(String subject, String role, String scope, Duration ttl, List<String> audience) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer("ledgerbank-identity").subject(subject).audience(audience)
                .issuedAt(now).expiresAt(now.plus(ttl)).claim("roles", List.of(role)).claim("scope", scope).build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(keys.publicKey().getKeyID()).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private void revokeFamily(UUID familyId) { sessions.findAllByFamilyId(familyId).forEach(s -> s.revokedAt = Instant.now()); }
    private ApiException invalidCredentials(String correlationId) { audit(null, "LOGIN", "FAILURE", correlationId); return new ApiException(401, "invalid_credentials", "Email or password is incorrect"); }
    private String normalizeEmail(String email) { return email.strip().toLowerCase(Locale.ROOT); }
    private String randomToken() { byte[] bytes = new byte[32]; random.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private void send(String to, String subject, String text) { SimpleMailMessage m = new SimpleMailMessage(); m.setTo(to); m.setSubject(subject); m.setText(text); mail.send(m); }
    private void audit(UUID userId, String action, String outcome, String correlationId) {
        jdbc.update("insert into auth_audit(user_id, action, outcome, correlation_id) values (?,?,?,?)", userId, action, outcome, correlationId);
    }

    record SessionTokens(String accessToken, String refreshToken, UUID sessionId, UUID userId, String email, String role, long expiresIn) {}
}
