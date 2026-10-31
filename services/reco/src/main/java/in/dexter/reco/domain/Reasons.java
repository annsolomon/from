package in.dexter.reco.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * The "top reasons" a result carries (MASTER_PLAN P14's explainability). Plain sentences built from the figures the
 * scorer already computed — never a restatement of the score, and never a claim the numbers do not support.
 *
 * <p>Deliberately in the domain rather than the view layer: a reason is a statement about the arithmetic, so it
 * belongs where the arithmetic is and can be asserted by a unit test.
 */
final class Reasons {
  private static final int MAX = 4;

  private Reasons() {}

  static List<String> of(CardTerms card, Savings.Result r, SpendVector spend, int fitBps) {
    List<String> out = new ArrayList<>(MAX);
    if (spend.isEmpty()) {
      out.add("No spend recorded yet, so this is based on the card's terms alone.");
      return List.copyOf(out);
    }

    // the biggest earning line, with the rate that earned it
    Savings.Line top = null;
    for (Savings.Line l : r.lines()) {
      if (top == null || l.annualRewardPaise() > top.annualRewardPaise()) {
        top = l;
      }
    }
    if (top != null && top.annualRewardPaise() > 0) {
      out.add("Earns " + rupees(top.annualRewardPaise()) + " a year on your " + top.categoryRoot()
          + " spend at " + pct(top.rateBps()) + (top.rewardType().equals("cashback") ? " cashback" : " in "
          + top.rewardType()) + ".");
    }

    // a cap that actually bound the result is the most useful thing a reader can know
    for (Savings.Line l : r.lines()) {
      if (l.capBound()) {
        out.add("The " + l.categoryRoot() + " reward is capped at " + rupees(l.annualCapPaise())
            + " a year, which your spend reaches.");
        break;
      }
    }

    Savings.Fee fee = r.fee();
    if (fee.annualFeePaise() > 0) {
      if (fee.waiverProbabilityBps() >= 10_000) {
        out.add("Your spend clears the " + rupees(fee.waiverSpendPaise()) + " waiver, so the "
            + rupees(fee.annualFeePaise()) + " fee is not counted.");
      } else if (fee.waiverProbabilityBps() > 0) {
        out.add("The " + rupees(fee.annualFeePaise()) + " fee is counted at "
            + pct(10_000 - fee.waiverProbabilityBps()) + ", since your spend is near the waiver.");
      } else {
        out.add("Costs " + rupees(fee.annualFeePaise()) + " a year, which this spend does not waive.");
      }
    }

    Savings.MilestoneValue ms = r.milestone();
    if (ms.annualValuePaise() > 0) {
      out.add("Reaches its milestone, worth " + rupees(ms.annualValuePaise()) + " a year.");
    } else if (ms.monthlyShortfallPaise() > 0 && out.size() < MAX) {
      out.add("Its milestone needs " + rupees(ms.monthlyShortfallPaise()) + " more a month, so it is not counted.");
    }

    if (out.size() < MAX && fitBps < 3_000) {
      out.add("Its rewards sit mostly outside where you spend.");
    }
    return List.copyOf(out.size() > MAX ? out.subList(0, MAX) : out);
  }

  /** Paise to a plain rupee string: 888488 -> "Rs 8,884.88". No locale, no currency symbol in a reason. */
  static String rupees(Long paise) {
    if (paise == null) {
      return "Rs 0";
    }
    long p = Math.abs(paise);
    String whole = group(p / 100);
    long fraction = p % 100;
    String s = "Rs " + whole + (fraction == 0 ? "" : "." + (fraction < 10 ? "0" : "") + fraction);
    return paise < 0 ? "-" + s : s;
  }

  /** Indian digit grouping: 1234567 -> "12,34,567". */
  static String group(long n) {
    String s = Long.toString(n);
    if (s.length() <= 3) {
      return s;
    }
    StringBuilder b = new StringBuilder(s.substring(s.length() - 3));
    String rest = s.substring(0, s.length() - 3);
    while (rest.length() > 2) {
      b.insert(0, rest.substring(rest.length() - 2) + ",");
      rest = rest.substring(0, rest.length() - 2);
    }
    return rest.isEmpty() ? b.toString() : rest + "," + b;
  }

  /** bps to a percentage: 150 -> "1.5%", 10000 -> "100%". */
  static String pct(int bps) {
    long whole = bps / 100;
    long fraction = Math.abs(bps % 100);
    if (fraction == 0) {
      return whole + "%";
    }
    return fraction % 10 == 0 ? whole + "." + fraction / 10 + "%"
        : whole + "." + (fraction < 10 ? "0" : "") + fraction + "%";
  }
}
