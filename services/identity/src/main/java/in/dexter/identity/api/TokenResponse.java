package in.dexter.identity.api;

import in.dexter.identity.app.TokenPair;
import java.time.Instant;
import java.util.UUID;

/** Login / refresh response (camelCase like every Dexter API; MASTER_PLAN 9). */
public record TokenResponse(String accessToken, String tokenType, long expiresIn, String refreshToken,
    Instant refreshExpiresAt, UUID sessionId) {
  static TokenResponse of(TokenPair p) {
    return new TokenResponse(p.accessToken(), p.tokenType(), p.expiresIn(), p.refreshToken(), p.refreshExpiresAt(),
        p.sessionId());
  }
}
