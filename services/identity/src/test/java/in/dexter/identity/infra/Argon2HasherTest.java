package in.dexter.identity.infra;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.core.error.DexterException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

class Argon2HasherTest {
  final Argon2Hasher hasher = new Argon2Hasher(4, Duration.ofSeconds(5));

  @Test
  void hashesWithTheOwaspBaselineAndVerifies() {
    String h = hasher.hash("Correct-Horse-9");
    assertThat(h).startsWith("$argon2id$v=19$m=19456,t=2,p=1$");
    assertThat(hasher.matches("Correct-Horse-9", h)).isTrue();
    assertThat(hasher.matches("correct-horse-9", h)).isFalse();
    assertThat(hasher.needsRehash(h)).isFalse();
    hasher.dummyMatch("anything");
  }

  @Test
  void weakerOldHashesAreFlaggedForRehash() {
    String old = new Argon2PasswordEncoder(16, 32, 1, 4096, 1).encode("Correct-Horse-9");
    assertThat(hasher.matches("Correct-Horse-9", old)).isTrue();
    assertThat(hasher.needsRehash(old)).isTrue();
  }

  @Test
  void theBulkheadAnswers503WhenSaturated() throws Exception {
    Argon2Hasher one = new Argon2Hasher(1, Duration.ofMillis(1));
    int n = 6;
    CountDownLatch go = new CountDownLatch(1);
    AtomicInteger refused = new AtomicInteger();
    List<Thread> threads = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      threads.add(Thread.ofVirtual().start(() -> {
        try {
          go.await();
          one.hash("Correct-Horse-9");
        } catch (DexterException e) {
          assertThat(e.errorCode().code()).isEqualTo("SERVICE_UNAVAILABLE");
          refused.incrementAndGet();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }));
    }
    go.countDown();
    for (Thread t : threads) {
      t.join();
    }
    assertThat(refused.get()).as("one permit, 1 ms wait, %d concurrent ~50 ms hashes", n).isPositive().isLessThan(n);
  }
}
