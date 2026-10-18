package in.dexter.catalog.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Source schedules: five-field cron in IST, at most hourly (pure). */
class SchedulesTest {

  @Test
  void validSchedulesAndTheirNextRun() {
    assertThat(Schedules.parse("0 3 * * *")).isPresent();
    assertThat(Schedules.parse("30 3 * * 1")).isPresent();
    assertThat(Schedules.parse("0 * * * *")).as("hourly is the limit").isPresent();
    // 03:00 IST = 21:30 UTC the day before
    assertThat(Schedules.next("0 3 * * *", Instant.parse("2026-10-18T00:00:00Z")))
        .isEqualTo(Instant.parse("2026-10-18T21:30:00Z"));
  }

  @Test
  void tooFrequentOrMalformedSchedulesAreRefused() {
    assertThat(Schedules.parse("*/5 * * * *")).isEmpty();
    assertThat(Schedules.parse("0,30 * * * *")).isEmpty();
    assertThat(Schedules.parse("0 0 3 * * *")).as("six fields").isEmpty();
    assertThat(Schedules.parse("nonsense")).isEmpty();
    assertThat(Schedules.parse(null)).isEmpty();
    assertThat(Schedules.next("bad", Instant.now())).isNull();
  }
}
