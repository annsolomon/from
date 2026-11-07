package in.dexter.billing.domain;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * What an account may use (MASTER_PLAN P16): the plan its entitling subscription is on - FREE when there is none -
 * with any unexpired per-account override laid over the plan's value for that key.
 */
public final class Entitlements {
  public static final String FREE = "FREE";

  /** An override as stored: one key, its value, and when it lapses. */
  public record Override(String key, Object value, Instant expiresAt) {}

  /** The tier and the effective map. Keys are sorted, so two equal maps compare (and hash) equal. */
  public record Effective(String tier, Map<String, Object> entitlements) {}

  private Entitlements() {}

  /** The tier an account is on: its subscription's plan while that subscription entitles, else FREE. */
  public static String tier(String subscriptionStatus, String planCode) {
    return subscriptionStatus != null && Subscriptions.ENTITLED.contains(subscriptionStatus) && planCode != null
        ? planCode : FREE;
  }

  /** The plan's map with every override still in force at {@code now} replacing the plan's value for its key. */
  public static Effective effective(String tier, Map<String, Object> planEntitlements, List<Override> overrides,
      Instant now) {
    Map<String, Object> m = new TreeMap<>(planEntitlements);
    for (Override o : overrides) {
      if (o.expiresAt().isAfter(now)) {
        m.put(o.key(), o.value());
      }
    }
    return new Effective(tier, Collections.unmodifiableMap(new LinkedHashMap<>(m)));
  }

  /** Only the overrides still in force, soonest to lapse first (what the API lists beside the map). */
  public static List<Override> active(List<Override> overrides, Instant now) {
    return overrides.stream().filter(o -> o.expiresAt().isAfter(now))
        .sorted((a, b) -> a.expiresAt().compareTo(b.expiresAt())).toList();
  }
}
