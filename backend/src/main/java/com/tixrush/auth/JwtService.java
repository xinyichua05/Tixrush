package com.tixrush.auth;

import com.tixrush.config.JwtProperties;
import com.tixrush.user.User;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Creates (signs) tokens. Checking tokens on incoming requests is done by Spring Security
 * using the JwtDecoder bean in SecurityConfig, so there's no "verify" method here.
 *
 * A JWT is header.payload.signature, base64-encoded. Anyone can READ the payload (paste one
 * into jwt.io), but nobody can CHANGE it without the secret, or the signature breaks.
 * So never put secrets in it.
 */
@Service
public class JwtService {

    private final JwtEncoder jwtEncoder;
    private final JwtProperties props;

    public JwtService(JwtEncoder jwtEncoder, JwtProperties props) {
        this.jwtEncoder = jwtEncoder;
        this.props = props;
    }

    public AuthResponse issueToken(User user) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(props.ttl());

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("tixrush")
                .subject(user.getId().toString())   // "who": later, Buy reads the user id from here
                .issuedAt(now)
                .expiresAt(expiresAt)               // the decoder rejects the token after this
                .claim("email", user.getEmail())
                .claim("role", user.getRole().name())
                .build();

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new AuthResponse(token, expiresAt, user.getRole().name());
    }
}
