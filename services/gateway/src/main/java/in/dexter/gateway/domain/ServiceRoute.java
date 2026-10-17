package in.dexter.gateway.domain;

import java.util.List;
import java.util.Objects;

/**
 * One gateway route: every path pattern (Spring {@code PathPattern} syntax) that belongs to {@code service}.
 * {@code sse}: a long-lived event stream — no circuit breaker, no response timeout (MASTER_PLAN P06 step 3).
 */
public record ServiceRoute(String id, String service, List<String> patterns, boolean sse) {
  public ServiceRoute {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(service, "service");
    patterns = List.copyOf(patterns);
    if (patterns.isEmpty()) {
      throw new IllegalArgumentException("route " + id + " has no paths");
    }
  }

  static ServiceRoute of(String service, String... patterns) {
    return new ServiceRoute(service, service, List.of(patterns), false);
  }
}
