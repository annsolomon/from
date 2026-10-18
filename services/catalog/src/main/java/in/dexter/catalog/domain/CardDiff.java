package in.dexter.catalog.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Field-level diff of an extraction against a card's published document (MASTER_PLAN P10), and its application on
 * approve. Only fields the extraction found are compared (an absent field is "not stated", never "removed"); a rule
 * change is keyed by its category ({@code rules/base} for the base rate, {@code rules/<path>} otherwise; merchant
 * rules are never touched) and only cashback rates are extracted. Pure.
 */
public final class CardDiff {
  public static final String BASE_RULE = "rules/base";
  static final int BASE_PRIORITY = 10;
  static final int CATEGORY_PRIORITY = 100;

  /** One change: {@code from} is the published value (null = absent), {@code to} the extracted one. */
  public record Change(String path, Object from, Object to, double confidence) {}

  private CardDiff() {}

  public static List<Change> compute(CardDocument base, CardExtractor.Extraction x) {
    List<Change> out = new ArrayList<>();
    x.fields().forEach((name, f) -> {
      Object current = field(base, name);
      if (!sameNumber(current, f.value())) {
        out.add(new Change(name, current, f.value(), f.confidence()));
      }
    });
    for (CardExtractor.RuleHit hit : x.rules()) {
      CardDocument.Rule r = rule(base, hit.categoryPath());
      Map<String, Object> to = rate("cashback", hit.rateBps());
      if (r == null) {
        out.add(new Change(rulePath(hit.categoryPath()), null, to, hit.confidence()));
      } else if (!"cashback".equals(r.rewardType()) || !Objects.equals(r.rateBps(), hit.rateBps())) {
        out.add(new Change(rulePath(hit.categoryPath()), rate(r.rewardType(), r.rateBps()), to, hit.confidence()));
      }
    }
    return out;
  }

  /** The document with the changes applied (the caller validates the result). */
  public static CardDocument apply(CardDocument d, List<Change> changes) {
    Long joining = d.joiningFeePaise();
    Long annual = d.annualFeePaise();
    Long waiver = d.feeWaiverSpendPaise();
    Integer apr = d.aprBps();
    Integer forex = d.forexMarkupBps();
    Integer fuel = d.fuelSurchargeWaiverBps();
    List<CardDocument.Rule> rules = new ArrayList<>(d.rules() == null ? List.of() : d.rules());
    for (Change c : changes) {
      switch (c.path()) {
        case "joiningFeePaise" -> joining = asLong(c.to());
        case "annualFeePaise" -> annual = asLong(c.to());
        case "feeWaiverSpendPaise" -> waiver = asLong(c.to());
        case "aprBps" -> apr = asInt(c.to());
        case "forexMarkupBps" -> forex = asInt(c.to());
        case "fuelSurchargeWaiverBps" -> fuel = asInt(c.to());
        default -> {
          if (!c.path().startsWith("rules/") || !(c.to() instanceof Map<?, ?> to)) {
            throw new IllegalArgumentException("unknown change path " + c.path());
          }
          String path = c.path().equals(BASE_RULE) ? null : c.path().substring("rules/".length());
          String type = String.valueOf(to.get("rewardType"));
          Integer bps = asInt(to.get("rateBps"));
          int at = indexOf(rules, path);
          if (at >= 0) {
            CardDocument.Rule r = rules.get(at);
            rules.set(at, new CardDocument.Rule(r.categoryPath(), r.merchantId(), r.merchantSlug(), type, bps,
                "cashback".equals(type) ? Long.valueOf(0) : r.pointValueMilliPaise(), r.capPaise(), r.capPeriod(),
                r.minTxnPaise(), r.excludedPaths(), r.priority()));
          } else {
            rules.add(new CardDocument.Rule(path, null, null, type, bps, 0L, null, null, 0L, List.of(),
                path == null ? BASE_PRIORITY : CATEGORY_PRIORITY));
          }
        }
      }
    }
    return new CardDocument(d.slug(), d.name(), d.issuerCode(), d.network(), d.tier(), joining, annual, waiver, apr,
        forex, fuel, d.features(), rules, d.milestone());
  }

  static String rulePath(String categoryPath) {
    return categoryPath == null ? BASE_RULE : "rules/" + categoryPath;
  }

  private static Map<String, Object> rate(String type, Integer bps) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("rewardType", type);
    m.put("rateBps", bps);
    return m;
  }

  private static CardDocument.Rule rule(CardDocument d, String path) {
    int i = indexOf(d.rules() == null ? List.of() : d.rules(), path);
    return i < 0 ? null : d.rules().get(i);
  }

  private static int indexOf(List<CardDocument.Rule> rules, String path) {
    for (int i = 0; i < rules.size(); i++) {
      CardDocument.Rule r = rules.get(i);
      if (r.merchantId() == null && r.merchantSlug() == null && Objects.equals(r.categoryPath(), path)) {
        return i;
      }
    }
    return -1;
  }

  static Object field(CardDocument d, String name) {
    return switch (name) {
      case "joiningFeePaise" -> d.joiningFeePaise();
      case "annualFeePaise" -> d.annualFeePaise();
      case "feeWaiverSpendPaise" -> d.feeWaiverSpendPaise();
      case "aprBps" -> d.aprBps();
      case "forexMarkupBps" -> d.forexMarkupBps();
      case "fuelSurchargeWaiverBps" -> d.fuelSurchargeWaiverBps();
      default -> throw new IllegalArgumentException("unknown field " + name);
    };
  }

  private static boolean sameNumber(Object a, Object b) {
    if (a == null || b == null) {
      return a == b;
    }
    return ((Number) a).longValue() == ((Number) b).longValue();
  }

  private static Long asLong(Object v) {
    return v == null ? null : ((Number) v).longValue();
  }

  private static Integer asInt(Object v) {
    return v == null ? null : ((Number) v).intValue();
  }
}
