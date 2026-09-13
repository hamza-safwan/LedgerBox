package dev.ledgerbank.identity;

import com.nimbusds.jose.jwk.JWKSet;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
class AuthController {
    private static final String ACCESS_COOKIE = "LB_ACCESS";
    private static final String REFRESH_COOKIE = "LB_REFRESH";
    private final AuthService auth;
    private final JwtKeyProvider keys;
    private final boolean secure;

    AuthController(AuthService auth, JwtKeyProvider keys, @Value("${ledgerbank.cookie-secure}") boolean secure) {
        this.auth = auth; this.keys = keys; this.secure = secure;
    }

    @PostMapping("/register")
    ResponseEntity<Map<String, Object>> register(@Valid @RequestBody RegisterRequest request,
                                                  @RequestHeader(value="X-Correlation-ID", required=false) String correlationId) {
        UUID userId = auth.register(request.email(), request.password(), correlationId);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("userId", userId, "status", "VERIFICATION_REQUIRED"));
    }

    @PostMapping("/verify-email")
    Map<String, String> verify(@Valid @RequestBody TokenRequest request,
                               @RequestHeader(value="X-Correlation-ID", required=false) String correlationId) {
        auth.verifyEmail(request.token(), correlationId); return Map.of("status", "VERIFIED");
    }

    @PostMapping("/login")
    ResponseEntity<SessionView> login(@Valid @RequestBody LoginRequest request,
                                      @RequestHeader(value="X-Correlation-ID", required=false) String correlationId) {
        return session(auth.login(request.email(), request.password(), correlationId));
    }

    @PostMapping("/refresh")
    ResponseEntity<SessionView> refresh(@CookieValue(name=REFRESH_COOKIE, required=false) String refresh,
                                        @RequestHeader(value="X-Correlation-ID", required=false) String correlationId) {
        if (refresh == null) throw new ApiException(401, "missing_session", "Session is missing");
        return session(auth.refresh(refresh, correlationId));
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(@CookieValue(name=REFRESH_COOKIE, required=false) String refresh,
                                @RequestHeader(value="X-Correlation-ID", required=false) String correlationId) {
        auth.logout(refresh, correlationId);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, clear(ACCESS_COOKIE, "/").toString())
                .header(HttpHeaders.SET_COOKIE, clear(REFRESH_COOKIE, "/api/v1/auth").toString()).build();
    }

    @GetMapping("/me")
    SessionView me(@AuthenticationPrincipal Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        return new SessionView(UUID.fromString(jwt.getSubject()), null, roles.get(0), 0);
    }

    @PostMapping("/password-reset")
    ResponseEntity<Void> passwordReset(@Valid @RequestBody PasswordResetRequest request) {
        if (request.token() == null) auth.requestPasswordReset(request.email());
        else auth.confirmPasswordReset(request.token(), request.newPassword());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/service-token")
    Map<String, Object> serviceToken(@Valid @RequestBody ServiceTokenRequest request) {
        return Map.of("accessToken", auth.serviceToken(request.clientId(), request.clientSecret()), "expiresIn", 300);
    }

    @GetMapping("/.well-known/jwks.json")
    Map<String, Object> jwks() { return new JWKSet(keys.publicKey()).toJSONObject(); }

    private ResponseEntity<SessionView> session(AuthService.SessionTokens tokens) {
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie(ACCESS_COOKIE, tokens.accessToken(), "/", Duration.ofSeconds(tokens.expiresIn())).toString())
                .header(HttpHeaders.SET_COOKIE, cookie(REFRESH_COOKIE, tokens.refreshToken(), "/api/v1/auth", Duration.ofDays(30)).toString())
                .body(new SessionView(tokens.userId(), tokens.email(), tokens.role(), tokens.expiresIn()));
    }

    private ResponseCookie cookie(String name, String value, String path, Duration age) {
        return ResponseCookie.from(name, value).httpOnly(true).secure(secure).sameSite("Lax").path(path).maxAge(age).build();
    }
    private ResponseCookie clear(String name, String path) { return cookie(name, "", path, Duration.ZERO); }

    record RegisterRequest(@Email @NotBlank String email, @Size(min=12, max=128) String password) {}
    record LoginRequest(@Email @NotBlank String email, @NotBlank String password) {}
    record TokenRequest(@NotBlank String token) {}
    record PasswordResetRequest(String email, String token, @Size(min=12, max=128) String newPassword) {
        @AssertTrue(message="provide either email or token and newPassword") boolean valid() {
            return (email != null && token == null && newPassword == null) || (email == null && token != null && newPassword != null);
        }
    }
    record ServiceTokenRequest(@NotBlank String clientId, @NotBlank String clientSecret) {}
    record SessionView(UUID userId, String email, String role, long expiresIn) {}
}

