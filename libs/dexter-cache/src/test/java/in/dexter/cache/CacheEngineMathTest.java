package in.dexter.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class CacheEngineMathTest {

  @Test
  void valuesRoundTripIncludingTheNotFoundMarker() {
    CacheValue v = new CacheValue(42, "{\"a\":\"x:y\"}");
    assertThat(v.encode()).isEqualTo("42:J{\"a\":\"x:y\"}");
    assertThat(CacheValue.decode(v.encode())).isEqualTo(v);
    CacheValue nf = new CacheValue(7, null);
    assertThat(nf.encode()).isEqualTo("7:N");
    assertThat(CacheValue.decode("7:N")).isEqualTo(nf);
    assertThat(CacheValue.decode("7:N").notFound()).isTrue();
    assertThat(CacheValue.decode("3:J")).isEqualTo(new CacheValue(3, ""));
  }

  @Test
  void malformedValuesDecodeToNull() {
    for (String bad : new String[] {"", ":", "x:J{}", "-1:J{}", "5:", "5:X{}", "5:Nextra", "J{}", "99999999999999999999:J{}"}) {
      assertThat(CacheValue.decode(bad)).as(bad).isNull();
    }
    assertThat(CacheValue.decode(null)).isNull();
  }

  @Test
  void xfetchNeverFiresWithFarExpiryOrBetaZero() {
    assertThat(CacheEngine.refreshEarly(10, 600_000, 1.0, 0.5)).isFalse();
    assertThat(CacheEngine.refreshEarly(10, 5, 0.0, 1e-9)).isFalse();
    assertThat(CacheEngine.refreshEarly(10, -1, 1.0, 1e-9)).isFalse();
    assertThat(CacheEngine.refreshEarly(10, 0, 1.0, 1e-9)).isFalse();
  }

  @Test
  void xfetchFiresNearExpiryInProportionToLoadTime() {
    // load 100 ms, 50 ms left: fires when -ln(u) >= 0.5, i.e. u <= e^-0.5 ~ 0.607
    assertThat(CacheEngine.refreshEarly(100, 50, 1.0, 0.60)).isTrue();
    assertThat(CacheEngine.refreshEarly(100, 50, 1.0, 0.61)).isFalse();
    // a slower load starts refreshing earlier
    assertThat(CacheEngine.refreshEarly(1000, 500, 1.0, 0.60)).isTrue();
    assertThat(CacheEngine.refreshEarly(100, 500, 1.0, 0.60)).isFalse();
  }

  @Test
  void jitterStaysWithinTenPercentAndAboveOneSecond() {
    Duration ttl = Duration.ofMinutes(10);
    assertThat(CacheEngine.jittered(ttl, 0.1, 0.0)).isEqualTo(Duration.ofMinutes(9));
    assertThat(CacheEngine.jittered(ttl, 0.1, 1.0)).isEqualTo(Duration.ofMinutes(11));
    assertThat(CacheEngine.jittered(ttl, 0.1, 0.5)).isEqualTo(ttl);
    assertThat(CacheEngine.jittered(Duration.ofSeconds(1), 0.1, 0.0)).isEqualTo(Duration.ofSeconds(1));
  }

  @Test
  void settingsAreValidated() {
    assertThat(CacheSettings.defaults().lockWait()).isEqualTo(Duration.ofMillis(150));
    assertThatThrownBy(() -> CacheSettings.defaults().withBreakerOpen(Duration.ofSeconds(-1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CacheSettings(Duration.ofSeconds(5), Duration.ofMillis(150), Duration.ofSeconds(30),
        0.9, Duration.ofSeconds(1), Duration.ofDays(7), 4096, 1.0)).isInstanceOf(IllegalArgumentException.class);
  }
}
