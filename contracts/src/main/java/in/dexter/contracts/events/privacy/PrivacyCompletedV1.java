package in.dexter.contracts.events.privacy;

import java.util.UUID;

/**
 * {@code <svc>.privacy.completed} v1 (MASTER_PLAN P18, ADR-0024): {@code service} ran {@code phase} of privacy
 * request {@code requestId} for the envelope's account. {@code rows} is how many rows the phase deleted, re-keyed,
 * froze or exported; {@code remaining} is how many of the account's rows the service still holds afterwards (0
 * after a completed erase); {@code partKey} is the object key of the service's JSON part ({@code export} only,
 * else null). Every participating service publishes it on its own topic {@code dexter.<svc>.privacy.v1}; the audit
 * service's saga consumes them all. Key = the account id.
 */
public record PrivacyCompletedV1(UUID requestId, String service, String phase, int attempt, long rows,
    long remaining, String partKey, String completedAt) {
  public static final int VERSION = 1;
  public static final String AGGREGATE = "privacy";

  /** {@code <svc>.privacy.completed}. */
  public static String type(String service) {
    return service + ".privacy.completed";
  }

  /** {@code dexter.<svc>.privacy.v1}. */
  public static String topic(String service) {
    return "dexter." + service + ".privacy.v1";
  }
}
