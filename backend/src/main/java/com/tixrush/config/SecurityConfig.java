package com.tixrush.config;

import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.beans.factory.annotation.Value;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * All security rules live here.
 *
 * How a request is checked:
 *   1. Every request passes through Spring Security's filter chain BEFORE reaching a controller.
 *   2. If it has "Authorization: Bearer <token>", the JwtDecoder checks the signature + expiry.
 *   3. The "role" claim becomes an authority like ROLE_ADMIN.
 *   4. authorizeHttpRequests decides: allowed, 401 (who are you?) or 403 (not allowed).
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties({JwtProperties.class, AdminProperties.class})
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   @Value("${tixrush.dev-tools.enabled:false}") boolean devTools) throws Exception {
        http
                // CSRF protection is for cookie-based logins. We send tokens in a header, so it's off.
                .csrf(csrf -> csrf.disable())
                // No server-side session: every request must carry its own token ("stateless").
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // First matching rule wins, so specific rules go first.
                        .requestMatchers("/api/auth/**", "/actuator/health", "/error").permitAll()
                        // Stripe calls this; it's protected by Stripe's signature (checked inside), not by a login.
                        .requestMatchers(HttpMethod.POST, "/api/payments/stripe/webhook").permitAll()
                        // The developer visualizer page: public with dev tools on, hidden from everyone otherwise.
                        .requestMatchers(HttpMethod.GET, "/", "/index.html", "/visualizer/**", "/favicon.ico")
                        .access((authentication, context) -> new AuthorizationDecision(devTools))
                        .requestMatchers(HttpMethod.GET, "/api/events", "/api/events/**").permitAll()
                        // Venues + their seating plans: everyone can look, only admins change them.
                        .requestMatchers(HttpMethod.GET, "/api/venues", "/api/venues/**").permitAll()
                        .requestMatchers("/api/venues", "/api/venues/**").hasRole("ADMIN")
                        // Buying: any logged-in user. Must come BEFORE the admin rule below.
                        .requestMatchers(HttpMethod.POST, "/api/events/*/purchases").authenticated()
                        .requestMatchers("/api/orders/**").authenticated() // your own orders + pay
                        .requestMatchers("/api/rush-demo", "/api/rush-demo/**").authenticated() // Live Rush page
                        .requestMatchers("/api/events", "/api/events/**").hasRole("ADMIN")
                        .requestMatchers("/api/admin/**", "/actuator/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                // Turn on "read a JWT from the Authorization header" support.
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())));
        return http.build();
    }

    /** Maps the token claim  "role": "ADMIN"  to the authority ROLE_ADMIN that hasRole() checks. */
    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("role");
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    /** BCrypt: slow on purpose and salted, so stolen hashes are very hard to crack. */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    // The same secret key both SIGNS tokens (encoder) and VERIFIES them (decoder): HMAC-SHA256.
    @Bean
    public JwtEncoder jwtEncoder(JwtProperties props) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(secretKey(props)));
    }

    @Bean
    public JwtDecoder jwtDecoder(JwtProperties props) {
        return NimbusJwtDecoder.withSecretKey(secretKey(props)).macAlgorithm(MacAlgorithm.HS256).build();
    }

    private static SecretKey secretKey(JwtProperties props) {
        return new SecretKeySpec(props.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }
}
