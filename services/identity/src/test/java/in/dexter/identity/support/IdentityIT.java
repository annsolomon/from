package in.dexter.identity.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The shared identity test context: throttles out of the way, because many ITs fail logins on purpose from one IP
 * (LockoutIT extends {@link IdentityBase} with production-like thresholds instead).
 */
public abstract class IdentityIT extends IdentityBase {
  @DynamicPropertySource
  static void relaxedThrottles(DynamicPropertyRegistry r) {
    r.add("dexter.identity.security.login.free", () -> "1000");
    r.add("dexter.identity.security.login.lock-after", () -> "1000");
    r.add("dexter.identity.security.mfa-free", () -> "1000");
    r.add("dexter.identity.security.mfa-lock-after", () -> "1000");
    r.add("dexter.identity.security.ip-failures", () -> "1000000");
  }
}
