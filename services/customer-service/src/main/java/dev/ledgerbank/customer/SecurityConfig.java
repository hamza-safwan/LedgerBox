package dev.ledgerbank.customer;

import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
class SecurityConfig {
    @Bean JwtDecoder decoder(@Value("${ledgerbank.jwk-set-uri}") String uri) { NimbusJwtDecoder decoder=NimbusJwtDecoder.withJwkSetUri(uri).build();decoder.setJwtValidator(validator());return decoder; }
    @Bean SecurityFilterChain chain(HttpSecurity http) throws Exception {
        Converter<Jwt, AbstractAuthenticationToken> converter = jwt -> {
            List<SimpleGrantedAuthority> authorities = new ArrayList<>();
            List<String> roles = jwt.getClaimAsStringList("roles");
            if (roles != null) roles.forEach(r -> authorities.add(new SimpleGrantedAuthority("ROLE_" + r)));
            return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
        };
        return http.csrf(c -> c.disable()).sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a.requestMatchers("/actuator/health/**", "/actuator/prometheus").permitAll()
                        .requestMatchers("/api/v1/ops/**").hasRole("OPERATOR")
                        .requestMatchers("/api/v1/customers/**","/api/v1/kyc-submissions").hasRole("CUSTOMER")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(converter))).build();
    }
    private OAuth2TokenValidator<Jwt> validator(){OAuth2TokenValidator<Jwt> issuer=JwtValidators.createDefaultWithIssuer("ledgerbank-identity");OAuth2TokenValidator<Jwt> audience=jwt->jwt.getAudience().contains("ledgerbank-api")?OAuth2TokenValidatorResult.success():OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token","Required audience is missing",null));return new DelegatingOAuth2TokenValidator<>(issuer,audience);}
}
