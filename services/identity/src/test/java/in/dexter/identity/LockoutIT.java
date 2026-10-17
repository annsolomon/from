package in.dexter.identity;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.identity.support.IdentityBase;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * MASTER_PLAN 7.1 lockout thresholds on the real endpoints (own context: 3 free failures, lock after 5, no delay so
 * the test needs no sleeping; the delay curve is proven in RedisLoginThrottleIT). A lock refuses even the right
 * password, looks the same for unknown emails, and raises ACCOUNT_LOCKED once.
 */
class LockoutIT extends IdentityBase {

  @DynamicPropertySource
  static void lockoutProps(DynamicPropertyRegistry r) {
    r.add("dexter.identity.security.login.free", () -> "3");
    r.add("dexter.identity.security.login.lock-after", () -> "5");
    r.add("dexter.identity.security.login.base-delay", () -> "0s");
    r.add("dexter.identity.security.login.max-delay", () -> "0s");
    r.add("dexter.identity.security.mfa-free", () -> "3");
    r.add("dexter.identity.security.mfa-lock-after", () -> "5");
    r.add("dexter.identity.security.ip-failures", () -> "1000000");
  }

  @Test
  void fiveFailuresLockTheEmailEvenForTheRightPassword() {
    String email = uniqueEmail("lock");
    signUpAndLogin(email);
    UUID account = accountIdOf(email);
    for (int i = 1; i <= 5; i++) {
      Res r = login(email, "Wrong-Password-" + i + "0000");
      assertThat(r.status()).as("failure " + i).isEqualTo(401);
    }
    Res locked = login(email, PASSWORD);
    assertThat(locked.status()).isEqualTo(429);
    assertThat(locked.code()).isEqualTo("RATE_LIMITED");
    assertThat(Long.parseLong(locked.header("Retry-After"))).isBetween(800L, 900L);
    assertThat(alertKinds(account)).containsOnlyOnce("ACCOUNT_LOCKED");
  }

  @Test
  void unknownEmailsAreThrottledTheSameWay() {
    String ghost = uniqueEmail("ghost");
    for (int i = 1; i <= 5; i++) {
      assertThat(login(ghost, "Wrong-Password-00000").status()).isEqualTo(401);
    }
    Res r = login(ghost, "Wrong-Password-00000");
    assertThat(r.status()).isEqualTo(429);
    assertThat(r.header("Retry-After")).isNotNull();
  }

  @Test
  void successBeforeTheLockResetsTheCount() {
    String email = uniqueEmail("reset");
    signUpAndLogin(email);
    for (int i = 0; i < 4; i++) {
      login(email, "Wrong-Password-00000");
    }
    assertThat(login(email, PASSWORD).status()).isEqualTo(200);
    for (int i = 0; i < 4; i++) {
      assertThat(login(email, "Wrong-Password-00000").status()).isEqualTo(401);
    }
    assertThat(login(email, PASSWORD).status()).as("count was reset").isEqualTo(200);
  }

  @Test
  void stepUpIsThrottledPerAccount() {
    String email = uniqueEmail("stepup-lock");
    String token = signUpAndLogin(email).get("accessToken").asString();
    for (int i = 0; i < 5; i++) {
      assertThat(post("/v1/auth/step-up", java.util.Map.of("password", "Wrong-Password-00000"), bearer(token)).status())
          .isEqualTo(401);
    }
    assertThat(post("/v1/auth/step-up", java.util.Map.of("password", PASSWORD), bearer(token)).status())
        .isEqualTo(429);
  }
}
