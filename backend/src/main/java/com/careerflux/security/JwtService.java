package com.careerflux.security;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

import javax.crypto.SecretKey;

import com.careerflux.config.CareerFluxProperties;
import com.careerflux.user.User;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Issues and validates the stateless access/refresh tokens. Tokens carry only
 * the user id, email and role: nothing sensitive, and nothing the server would
 * otherwise have to take the client's word for.
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);
    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_TYPE = "typ";
    private static final String TYPE_ACCESS = "access";
    private static final String TYPE_REFRESH = "refresh";

    private final SecretKey signingKey;
    private final CareerFluxProperties.Jwt config;

    public JwtService(CareerFluxProperties properties) {
        this.config = properties.security().jwt();
        byte[] secret = config.secret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            throw new IllegalStateException(
                    "CAREERFLUX_JWT_SECRET must be at least 32 characters. Set it in the environment.");
        }
        this.signingKey = Keys.hmacShaKeyFor(secret);
    }

    public String issueAccessToken(User user) {
        return issue(user, TYPE_ACCESS, config.accessTokenTtl().toSeconds());
    }

    public String issueRefreshToken(User user) {
        return issue(user, TYPE_REFRESH, config.refreshTokenTtl().toSeconds());
    }

    public long accessTokenSeconds() {
        return config.accessTokenTtl().toSeconds();
    }

    private String issue(User user, String type, long ttlSeconds) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.getId().toString())
                .claims(Map.of(
                        "email", user.getEmail(),
                        CLAIM_ROLE, user.getRole().name(),
                        CLAIM_TYPE, type))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(ttlSeconds)))
                .signWith(signingKey)
                .compact();
    }

    /**
     * Returns the parsed token, or {@code null} when it is expired, tampered with,
     * or of the wrong kind (a refresh token presented as an access token).
     */
    public ParsedToken parse(String token, boolean expectRefresh) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            String type = claims.get(CLAIM_TYPE, String.class);
            String expected = expectRefresh ? TYPE_REFRESH : TYPE_ACCESS;
            if (!expected.equals(type)) {
                return null;
            }
            return new ParsedToken(
                    UUID.fromString(claims.getSubject()),
                    claims.get("email", String.class),
                    claims.get(CLAIM_ROLE, String.class),
                    claims.getExpiration().toInstant());
        } catch (JwtException | IllegalArgumentException ex) {
            log.debug("Rejected token: {}", ex.getMessage());
            return null;
        }
    }

    public record ParsedToken(UUID userId, String email, String role, Instant expiresAt) {
    }
}
