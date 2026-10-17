package in.dexter.identity.app;

import java.time.Instant;
import java.util.UUID;

/** GET /v1/me. {@code phone} is the decrypted number (the caller owns it); {@code version} is also the ETag. */
public record MeView(UUID accountId, String email, String displayName, String phone, String tz, String tier,
    String status, boolean emailVerified, boolean mfaEnrolled, Instant createdAt, long version) {}
