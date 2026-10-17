package in.dexter.catalog.domain;

import in.dexter.core.error.FieldError;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Validation and normalisation of card documents (pure; the issuer's existence is checked by the caller). */
public final class CardDocuments {
  public static final Set<String> NETWORKS = Set.of("visa", "mastercard", "rupay", "amex", "diners");
  public static final Set<String> REWARD_TYPES = Set.of("cashback", "points", "miles");
  public static final Set<String> CAP_PERIODS = Set.of("txn", "statement", "month", "quarter", "year");
  public static final Set<String> MILESTONE_PERIODS = Set.of("statement", "month", "quarter", "year");
  public static final int MAX_RULES = 50;
  static final Pattern SLUG = Pattern.compile("[a-z0-9][a-z0-9-]{1,79}");
  static final Pattern PATH = Pattern.compile("[a-z][a-z0-9_]{0,31}(\\.[a-z][a-z0-9_]{0,31}){0,3}");
  static final Pattern FEATURE_KEY = Pattern.compile("[a-z][a-z0-9_]{0,39}");

  private CardDocuments() {}

  /** Field errors (empty = valid). */
  public static List<FieldError> validate(CardDocument d) {
    List<FieldError> e = new ArrayList<>();
    if (d == null) {
      e.add(new FieldError("body", "REQUIRED", "A card document"));
      return e;
    }
    if (d.slug() == null || !SLUG.matcher(d.slug()).matches()) {
      e.add(new FieldError("slug", "INVALID", "2-80 of [a-z0-9-], starting with a letter or digit"));
    }
    text(e, "name", d.name(), 120);
    if (d.issuerCode() == null || d.issuerCode().isBlank()) {
      e.add(new FieldError("issuerCode", "REQUIRED", "An issuer code (POST /v1/admin/catalog/issuers)"));
    }
    if (d.network() == null || !NETWORKS.contains(d.network())) {
      e.add(new FieldError("network", "INVALID", "One of " + String.join(", ", new java.util.TreeSet<>(NETWORKS))));
    }
    text(e, "tier", d.tier(), 40);
    nonNegative(e, "joiningFeePaise", d.joiningFeePaise());
    nonNegative(e, "annualFeePaise", d.annualFeePaise());
    nonNegative(e, "feeWaiverSpendPaise", d.feeWaiverSpendPaise());
    bps(e, "aprBps", d.aprBps());
    bps(e, "forexMarkupBps", d.forexMarkupBps());
    bps(e, "fuelSurchargeWaiverBps", d.fuelSurchargeWaiverBps());
    if (d.features() != null) {
      if (d.features().size() > 40) {
        e.add(new FieldError("features", "TOO_MANY", "At most 40 features"));
      }
      d.features().forEach((k, v) -> {
        if (!FEATURE_KEY.matcher(k).matches()
            || !(v instanceof Boolean || v instanceof Number || v instanceof String s && s.length() <= 200)) {
          e.add(new FieldError("features." + k, "INVALID", "snake_case key; boolean, number or short text"));
        }
      });
    }
    List<CardDocument.Rule> rules = d.rules() == null ? List.of() : d.rules();
    if (rules.size() > MAX_RULES) {
      e.add(new FieldError("rules", "TOO_MANY", "At most " + MAX_RULES + " rules"));
    }
    Set<String> scopes = new HashSet<>();
    for (int i = 0; i < rules.size(); i++) {
      String f = "rules[" + i + "].";
      CardDocument.Rule r = rules.get(i);
      if (r == null) {
        e.add(new FieldError("rules[" + i + "]", "REQUIRED", "A rule"));
        continue;
      }
      if (r.categoryPath() != null && !PATH.matcher(r.categoryPath()).matches()) {
        e.add(new FieldError(f + "categoryPath", "INVALID", "A category path"));
      }
      if (r.rewardType() == null || !REWARD_TYPES.contains(r.rewardType())) {
        e.add(new FieldError(f + "rewardType", "INVALID", "cashback, points or miles"));
      }
      if (r.rateBps() == null || r.rateBps() < 0 || r.rateBps() > 10_000) {
        e.add(new FieldError(f + "rateBps", "OUT_OF_RANGE", "0-10000 bps of spend"));
      }
      nonNegative(e, f + "pointValueMilliPaise", r.pointValueMilliPaise());
      nonNegative(e, f + "minTxnPaise", r.minTxnPaise());
      if ((r.capPaise() == null) != (r.capPeriod() == null)) {
        e.add(new FieldError(f + "capPaise", "CAP_SHAPE", "capPaise and capPeriod come together"));
      } else if (r.capPaise() != null && (r.capPaise() < 1 || !CAP_PERIODS.contains(r.capPeriod()))) {
        e.add(new FieldError(f + "capPeriod", "INVALID", "A positive cap and one of " + CAP_PERIODS));
      }
      if (r.excludedPaths() != null) {
        for (String p : r.excludedPaths()) {
          if (p == null || !PATH.matcher(p).matches()) {
            e.add(new FieldError(f + "excludedPaths", "INVALID", "Category paths"));
            break;
          }
        }
      }
      String scope = r.categoryPath() + "|" + r.merchantId();
      if (!scopes.add(scope)) {
        e.add(new FieldError(f + "categoryPath", "DUPLICATE_SCOPE", "Another rule has the same category and merchant"));
      }
    }
    CardDocument.Milestone m = d.milestone();
    if (m != null) {
      if (m.spendThresholdPaise() == null || m.spendThresholdPaise() < 1) {
        e.add(new FieldError("milestone.spendThresholdPaise", "INVALID", "A positive spend"));
      }
      if (m.period() == null || !MILESTONE_PERIODS.contains(m.period())) {
        e.add(new FieldError("milestone.period", "INVALID", "One of " + MILESTONE_PERIODS));
      }
      text(e, "milestone.benefitType", m.benefitType(), 40);
      nonNegative(e, "milestone.benefitValuePaise", m.benefitValuePaise());
    }
    return e;
  }

  /** Defaults filled, features ordered: the canonical form that is hashed and published. */
  public static CardDocument normalize(CardDocument d) {
    List<CardDocument.Rule> rules = new ArrayList<>();
    for (CardDocument.Rule r : d.rules() == null ? List.<CardDocument.Rule>of() : d.rules()) {
      rules.add(new CardDocument.Rule(r.categoryPath(), r.merchantId(), r.merchantSlug(), r.rewardType(), r.rateBps(),
          r.pointValueMilliPaise() == null ? 0L : r.pointValueMilliPaise(), r.capPaise(), r.capPeriod(),
          r.minTxnPaise() == null ? 0L : r.minTxnPaise(),
          r.excludedPaths() == null ? List.of() : List.copyOf(r.excludedPaths()),
          r.priority() == null ? 100 : r.priority()));
    }
    return new CardDocument(d.slug(), d.name().strip(), d.issuerCode(), d.network(), d.tier().strip(),
        d.joiningFeePaise() == null ? 0L : d.joiningFeePaise(), d.annualFeePaise() == null ? 0L : d.annualFeePaise(),
        d.feeWaiverSpendPaise(), d.aprBps(), d.forexMarkupBps(), d.fuelSurchargeWaiverBps(),
        d.features() == null ? new TreeMap<>() : new TreeMap<>(d.features()), rules, d.milestone());
  }

  private static void text(List<FieldError> e, String field, String v, int max) {
    if (v == null || v.isBlank() || v.strip().length() > max) {
      e.add(new FieldError(field, v == null ? "REQUIRED" : "INVALID", "1-" + max + " characters"));
    }
  }

  private static void nonNegative(List<FieldError> e, String field, Long v) {
    if (v != null && v < 0) {
      e.add(new FieldError(field, "NEGATIVE", "Must not be negative"));
    }
  }

  private static void bps(List<FieldError> e, String field, Integer v) {
    if (v != null && (v < 0 || v > 10_000)) {
      e.add(new FieldError(field, "OUT_OF_RANGE", "0-10000 bps"));
    }
  }
}
