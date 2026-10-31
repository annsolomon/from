package in.dexter.reco.domain;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * What an account spends, by category root, as monthly averages in paise (MASTER_PLAN P14). This is the scorer's
 * only view of the account: the engine never reads a database.
 *
 * <p>Roots are ltree first labels ({@code food}, {@code travel}), because a rule that earns on {@code food.dining}
 * is matched against the {@code food} root's spend. That loses the sub-category detail the catalog's rules can
 * carry; {@link Rules#share} is where that approximation is made explicit.
 */
public record SpendVector(Map<String, Long> monthlyByRoot) {

  public SpendVector {
    monthlyByRoot = monthlyByRoot == null ? Map.of() : Map.copyOf(monthlyByRoot);
    for (Map.Entry<String, Long> e : monthlyByRoot.entrySet()) {
      if (e.getValue() == null || e.getValue() < 0) {
        throw new IllegalArgumentException("monthly spend must not be negative: " + e.getKey());
      }
    }
  }

  public static SpendVector of(Map<String, Long> monthlyByRoot) {
    return new SpendVector(monthlyByRoot);
  }

  public static SpendVector empty() {
    return new SpendVector(Map.of());
  }

  public long monthly(String root) {
    Long v = monthlyByRoot.get(root);
    return v == null ? 0 : v;
  }

  /** Total monthly spend across every root. */
  public long monthlyTotal() {
    long t = 0;
    for (long v : monthlyByRoot.values()) {
      t += v;
    }
    return t;
  }

  public boolean isEmpty() {
    return monthlyTotal() == 0;
  }

  /** The root with the most spend, for the "top reasons" wording. */
  public Optional<String> topRoot() {
    String best = null;
    long most = -1;
    for (Map.Entry<String, Long> e : monthlyByRoot.entrySet()) {
      // ties break on the root name so the explanation is deterministic
      if (e.getValue() > most || (e.getValue() == most && best != null && e.getKey().compareTo(best) < 0)) {
        most = e.getValue();
        best = e.getKey();
      }
    }
    return Optional.ofNullable(best);
  }

  /** The roots in descending spend, then by name — the order every breakdown is written in. */
  public Map<String, Long> descending() {
    return monthlyByRoot.entrySet().stream()
        .sorted((a, b) -> a.getValue().equals(b.getValue()) ? a.getKey().compareTo(b.getKey())
            : Long.compare(b.getValue(), a.getValue()))
        .collect(LinkedHashMap::new, (m, e) -> m.put(e.getKey(), e.getValue()), LinkedHashMap::putAll);
  }
}
