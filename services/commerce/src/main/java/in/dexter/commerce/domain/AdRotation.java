package in.dexter.commerce.domain;

import java.util.List;
import java.util.UUID;

/**
 * Weighted rotation (MASTER_PLAN P17): each live (campaign, creative) pair is chosen with probability proportional to
 * {@code campaign.weight × creative.weight}. The roll comes from the caller (a uniform draw in
 * {@code [0, totalWeight)}), so the choice itself is a pure function and testable without randomness.
 */
public final class AdRotation {
  public record Option(UUID campaignId, UUID creativeId, long weight) {
    public Option {
      if (weight < 1) {
        throw new IllegalArgumentException("weight must be positive");
      }
    }
  }

  private AdRotation() {}

  public static long totalWeight(List<Option> options) {
    long t = 0;
    for (Option o : options) {
      t = Math.addExact(t, o.weight());
    }
    return t;
  }

  public static Option pick(List<Option> options, long roll) {
    if (options.isEmpty()) {
      throw new IllegalArgumentException("no options");
    }
    long total = totalWeight(options);
    if (roll < 0 || roll >= total) {
      throw new IllegalArgumentException("roll must be in [0, " + total + ")");
    }
    long acc = 0;
    for (Option o : options) {
      acc += o.weight();
      if (roll < acc) {
        return o;
      }
    }
    throw new IllegalStateException("unreachable");
  }
}
