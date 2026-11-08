package in.dexter.commerce.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * A deal's saving at a given spend: every component on the full spend, each capped separately, summed, and never
 * more than the spend. The P17 golden: ₹75,000 with 10% capped at ₹5,000 and 5% capped at ₹2,000 saves ₹7,000
 * (₹7,500 capped to ₹5,000, plus ₹3,750 capped to ₹2,000) — not 15% of anything, and not one component applied to
 * what the other left.
 */
public final class Savings {
  public static final int MAX_COMPONENTS = 5;

  public record Line(SavingComponent component, long savingPaise) {}

  public record Breakdown(long spendPaise, List<Line> lines, long totalPaise) {
    public Breakdown {
      lines = List.copyOf(lines);
    }
  }

  private Savings() {}

  public static Breakdown of(List<SavingComponent> components, long spendPaise) {
    if (components.isEmpty() || components.size() > MAX_COMPONENTS) {
      throw new IllegalArgumentException("a deal has 1.." + MAX_COMPONENTS + " components");
    }
    if (spendPaise < 0 || spendPaise > SavingComponent.MAX_PAISE) {
      throw new IllegalArgumentException("spendPaise must be 0.." + SavingComponent.MAX_PAISE);
    }
    List<Line> lines = new ArrayList<>(components.size());
    long total = 0;
    for (SavingComponent c : components) {
      long s = c.savingFor(spendPaise);
      lines.add(new Line(c, s));
      total = Math.addExact(total, s);
    }
    return new Breakdown(spendPaise, lines, Math.min(total, spendPaise));
  }

  public static long total(List<SavingComponent> components, long spendPaise) {
    return of(components, spendPaise).totalPaise();
  }
}
