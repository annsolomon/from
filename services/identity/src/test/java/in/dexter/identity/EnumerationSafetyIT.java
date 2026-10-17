package in.dexter.identity;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.identity.support.IdentityIT;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * MASTER_PLAN P04 DoD "no endpoint leaks whether an email exists": same status and body for known and unknown
 * emails on login, registration and forgot-password, and login timing within tolerance (both paths run one
 * Argon2 verification).
 */
class EnumerationSafetyIT extends IdentityIT {

  @Test
  void loginAnswersAreIdenticalForUnknownEmailAndWrongPassword() {
    String known = uniqueEmail("known");
    signUpAndLogin(known);
    Res wrongPassword = login(known, "Not-The-Password-1");
    Res unknownEmail = login(uniqueEmail("nobody"), "Not-The-Password-1");
    Res malformedEmail = login("not-an-email", "Not-The-Password-1");
    for (Res r : List.of(wrongPassword, unknownEmail, malformedEmail)) {
      assertThat(r.status()).isEqualTo(401);
      assertThat(r.code()).isEqualTo("INVALID_CREDENTIALS");
      assertThat(r.json().get("detail").asString()).isEqualTo("The email or password is incorrect");
    }
  }

  @Test
  void registrationOfAnExistingEmailLooksExactlyLikeANewOne() {
    String email = uniqueEmail("dup");
    signUpAndLogin(email);
    long accountsBefore = ADMIN.sql("SELECT count(1) FROM identity.accounts").query(Long.class).single();
    Res dup = register(email, "Some-Other-Password-5");
    Res fresh = register(uniqueEmail("fresh"), PASSWORD);
    assertThat(dup.status()).isEqualTo(202);
    assertThat(dup.body()).isEqualTo(fresh.body());
    assertThat(ADMIN.sql("SELECT count(1) FROM identity.accounts").query(Long.class).single())
        .isEqualTo(accountsBefore + 1);
    assertThat(awaitMail(email, "already have a Dexter account", Duration.ofSeconds(5))).isTrue();
    assertThat(login(email, "Some-Other-Password-5").status()).as("existing password unchanged").isEqualTo(401);
  }

  @Test
  void forgotPasswordAlwaysAccepts() {
    String known = uniqueEmail("fk");
    signUpAndLogin(known);
    Res a = post("/v1/auth/password/forgot", Map.of("email", known));
    Res b = post("/v1/auth/password/forgot", Map.of("email", uniqueEmail("fu")));
    Res c = post("/v1/auth/password/forgot", Map.of("email", "garbage"));
    for (Res r : List.of(a, b, c)) {
      assertThat(r.status()).isEqualTo(202);
      assertThat(r.body()).isEqualTo(a.body());
    }
  }

  @Test
  void loginTimingDoesNotRevealUnknownEmails() {
    String known = uniqueEmail("timing");
    signUpAndLogin(known);
    String unknown = uniqueEmail("timing-x");
    for (int i = 0; i < 3; i++) { // warm-up
      login(known, "Wrong-Password-000");
      login(unknown, "Wrong-Password-000");
    }
    long knownMs = medianMillis(() -> login(known, "Wrong-Password-000"));
    long unknownMs = medianMillis(() -> login(unknown, "Wrong-Password-000"));
    long tolerance = Math.max(40, Math.max(knownMs, unknownMs) / 2);
    assertThat(Math.abs(knownMs - unknownMs)).as("median known=%dms unknown=%dms", knownMs, unknownMs)
        .isLessThanOrEqualTo(tolerance);
  }

  private static long medianMillis(Supplier<Res> call) {
    List<Long> samples = new ArrayList<>();
    for (int i = 0; i < 9; i++) {
      long t0 = System.nanoTime();
      Res r = call.get();
      samples.add((System.nanoTime() - t0) / 1_000_000);
      assertThat(r.status()).isEqualTo(401);
    }
    Collections.sort(samples);
    return samples.get(samples.size() / 2);
  }
}
