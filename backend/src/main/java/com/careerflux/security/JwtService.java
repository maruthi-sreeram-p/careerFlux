package com.careerflux.security;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

import javax.crypto.SecretKey;

import com.careerflux.config.CareerFluxProperties;
import com.careerflux.config.DeploymentProfiles;
import com.careerflux.user.User;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import org.springframework.core.env.Environment;

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
    /** Issue time in epoch milliseconds, for the session watermark. */
    private static final String CLAIM_ISSUED_AT_MILLIS = "iat_ms";
    private static final String TYPE_ACCESS = "access";
    private static final String TYPE_REFRESH = "refresh";

    private final SecretKey signingKey;
    private final CareerFluxProperties.Jwt config;

    /**
     * The value application.yml falls back to when the environment is silent.
     *
     * <p>It is published in this repository, so a token signed with it can be
     * forged by anyone who has read the source — for any user, in any
     * institution. It is long enough to pass a length check, which is exactly
     * why a length check alone was not protection.
     */
    private static final String DEVELOPMENT_FALLBACK_SECRET =
            "dev-only-insecure-secret-change-me-0123456789abcdef";

    public JwtService(CareerFluxProperties properties, Environment environment) {
        this.config = properties.security().jwt();
        String secretValue = config.secret();
        byte[] secret = secretValue.getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            throw new IllegalStateException(
                    "CAREERFLUX_JWT_SECRET must be at least 32 characters. Set it in the environment.");
        }
        // Length was never the risk. A known value is, and this one is in the
        // repository: without this check a deployment that simply forgot to set
        // the variable would sign real sessions with a public key and start
        // perfectly happily. Only an explicitly named dev or test profile may
        // use it; no profile at all used to count as dev, and no longer does.
        if (isPublishedDevelopmentSecret(secretValue) && !DeploymentProfiles.isDevelopment(environment)) {
            throw new IllegalStateException(
                    "CAREERFLUX_JWT_SECRET is still the built-in development value, which is public "
                            + "in the source repository. Set a real secret in the environment "
                            + "(openssl rand -base64 48) before running outside dev or test.");
        }
        if (DEVELOPMENT_FALLBACK_SECRET.equals(secretValue)) {
            log.warn("Signing tokens with the built-in development secret. This is fine locally "
                    + "and unacceptable anywhere else.");
        }
        this.signingKey = Keys.hmacShaKeyFor(secret);
    }

    /**
     * Whether a value is the signing secret published in this repository.
     *
     * <p>Public so the startup guard can refuse it before the application
     * context exists, not only once this service is built.
     */
    public static boolean isPublishedDevelopmentSecret(String value) {
        return DEVELOPMENT_FALLBACK_SECRET.equals(value);
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
                        // Informational only. Authorization reads the role from the
                        // database on every request and never from here.
                        CLAIM_ROLE, user.getRole().name(),
                        CLAIM_TYPE, type,
                        // The standard iat claim is whole seconds. The session
                        // watermark needs to tell apart a token issued just before a
                        // password reset from the one issued by the sign-in just
                        // after it, which can fall in the same second.
                        CLAIM_ISSUED_AT_MILLIS, now.toEpochMilli()))
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
                    issuedAt(claims),
                    claims.getExpiration().toInstant());
        } catch (JwtException | IllegalArgumentException ex) {
            log.debug("Rejected token: {}", ex.getMessage());
            return null;
        }
    }

    /**
     * When the token was issued, to the millisecond where the token says so.
     *
     * <p>Tokens issued before the millisecond claim existed fall back to the
     * standard whole-second {@code iat}; with neither, the answer is null, which
     * the session watermark treats as too old to honour once anything has been
     * revoked.
     */
    private static Instant issuedAt(Claims claims) {
        Number millis = claims.get(CLAIM_ISSUED_AT_MILLIS, Number.class);
        if (millis != null) {
            return Instant.ofEpochMilli(millis.longValue());
        }
        return claims.getIssuedAt() == null ? null : claims.getIssuedAt().toInstant();
    }

    /**
     * @param role     the role the token was issued under. Informational: nothing
     *                 authorizes against it
     * @param issuedAt compared with the account's session watermark
     */
    public record ParsedToken(UUID userId, String email, String role, Instant issuedAt, Instant expiresAt) {
    }
}
