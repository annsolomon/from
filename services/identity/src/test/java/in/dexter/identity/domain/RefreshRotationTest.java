package in.dexter.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.identity.domain.RefreshRotation.Outcome;
import in.dexter.identity.domain.RefreshRotation.State;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RefreshRotationTest {
  static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");
  static final Instant LATER = NOW.plus(Duration.ofDays(1));
  static final Instant ABS = NOW.plus(Duration.ofDays(60));

  @Test
  void decisionTable() {
    assertThat(RefreshRotation.decide(new State(LATER, ABS, null, null, false), NOW)).isEqualTo(Outcome.ROTATE);
    assertThat(RefreshRotation.decide(new State(LATER, ABS, NOW, null, false), NOW)).isEqualTo(Outcome.REUSED);
    assertThat(RefreshRotation.decide(new State(LATER, ABS, NOW, NOW, false), NOW)).as("revoked wins")
        .isEqualTo(Outcome.REVOKED);
    assertThat(RefreshRotation.decide(new State(LATER, ABS, null, null, true), NOW)).isEqualTo(Outcome.REVOKED);
    assertThat(RefreshRotation.decide(new State(NOW, ABS, null, null, false), NOW)).as("idle expiry is exclusive")
        .isEqualTo(Outcome.EXPIRED);
    assertThat(RefreshRotation.decide(new State(LATER, NOW, null, null, false), NOW)).isEqualTo(Outcome.EXPIRED);
  }

  @Test
  void slidingExpiryIsCappedByTheAbsoluteLimit() {
    assertThat(RefreshRotation.nextExpiry(NOW, Duration.ofDays(30), ABS)).isEqualTo(NOW.plus(Duration.ofDays(30)));
    Instant abs = NOW.plus(Duration.ofDays(5));
    assertThat(RefreshRotation.nextExpiry(NOW, Duration.ofDays(30), abs)).isEqualTo(abs);
  }
}
