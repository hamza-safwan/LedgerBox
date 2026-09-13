package dev.ledgerbank.identity;

import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
class SecurityConfig {
    @Bean PasswordEncoder passwordEncoder() { return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8(); }

    @Bean JwtEncoder jwtEncoder(JwtKeyProvider keys) {
        return new NimbusJwtEncoder(new ImmutableJWKSet<SecurityContext>(new com.nimbusds.jose.jwk.JWKSet(keys.privateKey())));
    }

    @Bean JwtDecoder jwtDecoder(JwtKeyProvider keys) throws Exception {
        NimbusJwtDecoder decoder=NimbusJwtDecoder.withPublicKey(keys.publicKey().toRSAPublicKey()).build();
        OAuth2TokenValidator<Jwt> issuer=JwtValidators.createDefaultWithIssuer("ledgerbank-identity");
        OAuth2TokenValidator<Jwt> audience=jwt->jwt.getAudience().contains("ledgerbank-api")?OAuth2TokenValidatorResult.success():OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token","Required audience is missing",null));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(issuer,audience));
        return decoder;
    }

    @Bean SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/auth/me").authenticated()
                        .requestMatchers("/api/v1/auth/**", "/actuator/health/**", "/actuator/prometheus").permitAll()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> {})).build();
    }
}
