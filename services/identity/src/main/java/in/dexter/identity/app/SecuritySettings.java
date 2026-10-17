package in.dexter.identity.app;

import in.dexter.identity.domain.ThrottlePolicy;
import java.time.Duration;

/**
 * P05 settings (from {@code dexter.identity.security.*} and {@code dexter.identity.admin.*}).
 *
 * @param mfaChallengeTtl   lifetime of an {@code mfaToken} between password and second factor
 * @param mfaMaxAttempts    wrong codes per challenge before it is burnt
 * @param totpWindow        accepted clock drift in 30-second steps (each side)
 * @param adminRefreshIdle  admin console sliding window (stricter than users)
 * @param elevationMax      break-glass duration (≤ 1 h, the table also enforces it)
 * @param inviteTtl         admin invitation link lifetime
 */
public record SecuritySettings(
    Duration mfaChallengeTtl,
    int mfaMaxAttempts,
    int totpWindow,
    String totpIssuer,
    ThrottlePolicy loginPolicy,
    ThrottlePolicy mfaPolicy,
    ThrottlePolicy ipPolicy,
    String adminClientId,
    Duration adminRefreshIdle,
    Duration adminRefreshAbsolute,
    Duration elevationMax,
    Duration inviteTtl,
    String inviteLink) {

  public SecuritySettings {
    if (elevationMax.compareTo(Duration.ofHours(1)) > 0 || elevationMax.isNegative() || elevationMax.isZero()) {
      throw new IllegalArgumentException("break-glass elevation lasts at most 1 hour");
    }
    if (mfaMaxAttempts < 1 || totpWindow < 0 || totpWindow > 2) {
      throw new IllegalArgumentException("mfaMaxAttempts >= 1, totpWindow 0..2");
    }
  }
}
