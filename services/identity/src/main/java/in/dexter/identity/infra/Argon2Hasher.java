package in.dexter.identity.infra;

import in.dexter.core.error.CoreErrors;
import in.dexter.core.error.DexterException;
import in.dexter.identity.app.PasswordHasher;
import in.dexter.web.error.ProblemDetailsAdvice;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

/**
 * Argon2id with the OWASP baseline (m=19 MiB, t=2, p=1; MASTER_PLAN 7.1), the parameters the seed uses
 * (ADR-0008). Each hash allocates ~19 MiB, so concurrent hashing is bounded by a semaphore (bulkhead); callers
 * that cannot get a permit within {@code maxWait} get 503 + Retry-After instead of exhausting the heap.
 */
public final class Argon2Hasher implements PasswordHasher {
  public static final int SALT = 16;
  public static final int HASH = 32;
  public static final int PARALLELISM = 1;
  public static final int MEMORY_KIB = 19_456;
  public static final int ITERATIONS = 2;

  private final Argon2PasswordEncoder encoder;
  private final Semaphore permits;
  private final Duration maxWait;
  private final String dummyHash;

  public Argon2Hasher(int maxConcurrent, Duration maxWait) {
    this(new Argon2PasswordEncoder(SALT, HASH, PARALLELISM, MEMORY_KIB, ITERATIONS), maxConcurrent, maxWait);
  }

  Argon2Hasher(Argon2PasswordEncoder encoder, int maxConcurrent, Duration maxWait) {
    this.encoder = encoder;
    this.permits = new Semaphore(Math.max(1, maxConcurrent), true);
    this.maxWait = maxWait;
    this.dummyHash = encoder.encode("dummy-" + UUID.randomUUID());
  }

  @Override
  public String hash(String raw) {
    return bounded(() -> encoder.encode(raw));
  }

  @Override
  public boolean matches(String raw, String hash) {
    return bounded(() -> encoder.matches(raw, hash));
  }

  @Override
  public boolean needsRehash(String hash) {
    return encoder.upgradeEncoding(hash);
  }

  @Override
  public void dummyMatch(String raw) {
    bounded(() -> encoder.matches(raw, dummyHash));
  }

  private <T> T bounded(Supplier<T> work) {
    boolean acquired;
    try {
      acquired = permits.tryAcquire(maxWait.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      acquired = false;
    }
    if (!acquired) {
      throw new DexterException(CoreErrors.SERVICE_UNAVAILABLE, "Too many sign-ins at once; try again shortly",
          List.of(), Map.of(ProblemDetailsAdvice.RETRY_AFTER_PROPERTY, 1), null);
    }
    try {
      return work.get();
    } finally {
      permits.release();
    }
  }
}
