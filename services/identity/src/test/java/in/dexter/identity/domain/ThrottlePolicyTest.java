package in.dexter.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class ThrottlePolicyTest {
  @Test
  void defaultsMatchMasterPlan() {
    ThrottlePolicy p = ThrottlePolicy.login();
    assertThat(p.lockAfter()).isEqualTo(10);
    assertThat(p.lockFor()).isEqualTo(Duration.ofMinutes(15));
    assertThat(p.freeFailures()).isLessThan(p.lockAfter());
  }

  @Test
  void invalidPoliciesAreRejected() {
    assertThatThrownBy(() -> new ThrottlePolicy(11, Duration.ZERO, Duration.ZERO, 10, Duration.ofMinutes(1),
        Duration.ofMinutes(1))).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ThrottlePolicy(0, Duration.ZERO, Duration.ZERO, 0, Duration.ofMinutes(1),
        Duration.ofMinutes(1))).isInstanceOf(IllegalArgumentException.class);
  }
}
