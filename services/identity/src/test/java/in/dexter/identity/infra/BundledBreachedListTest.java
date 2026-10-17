package in.dexter.identity.infra;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BundledBreachedListTest {
  @Test
  void loadsTheBundledListAndMatchesCaseInsensitively() {
    BundledBreachedList list = new BundledBreachedList();
    assertThat(list.size()).isGreaterThan(9_000);
    assertThat(list.contains("password123")).isTrue();
    assertThat(list.contains("PassWord123")).isTrue();
    assertThat(list.contains("qwertyuiop")).isTrue();
    assertThat(list.contains("Asha-Demo-2026!")).as("demo passwords stay usable").isFalse();
    assertThat(list.contains("Correct-Horse-Battery-9")).isFalse();
  }
}
