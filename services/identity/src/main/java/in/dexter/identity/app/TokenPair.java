package in.dexter.identity.app;

import java.time.Instant;
import java.util.UUID;

/** Login / refresh result (MASTER_PLAN 7.1): a 10-minute access token and a rotating opaque refresh token. */
public record TokenPair(String accessToken, String tokenType, long expiresIn, String refreshToken,
    Instant refreshExpiresAt, UUID sessionId) {}
