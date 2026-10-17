package in.dexter.identity.infra;

import in.dexter.core.error.CoreErrors;
import in.dexter.core.error.DexterException;
import in.dexter.web.error.ProblemDetailsAdvice;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;

/**
 * Sign-in depends on Redis (throttles, MFA challenges). When Redis is unreachable it fails closed: 503 with
 * Retry-After, never an unthrottled password check.
 */
final class RedisCalls {
  private static final Logger log = LoggerFactory.getLogger(RedisCalls.class);

  private RedisCalls() {}

  static <T> T call(Supplier<T> work) {
    try {
      return work.get();
    } catch (DataAccessException e) {
      log.error("REDIS_UNAVAILABLE {}", e.getClass().getSimpleName());
      throw new DexterException(CoreErrors.SERVICE_UNAVAILABLE, "Sign-in is temporarily unavailable", List.of(),
          Map.of(ProblemDetailsAdvice.RETRY_AFTER_PROPERTY, 2), e);
    }
  }
}
