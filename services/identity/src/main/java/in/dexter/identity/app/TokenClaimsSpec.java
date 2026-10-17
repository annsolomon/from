package in.dexter.identity.app;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Everything of an access token except {@code iss}, {@code iat}, {@code exp}, {@code jti} and the header, which the
 * signer adds (contract: dexter-security {@code TokenClaims}, A-18). Null fields are omitted.
 */
public record TokenClaimsSpec(String subject, List<String> audiences, String clientId, String sessionId, String scope,
    List<String> roles, List<String> amr, Instant authTime, Boolean mfaEnrolled, String tz, String tier,
    Duration ttl, String issuer, Instant issuedAt, String tokenId) {}
