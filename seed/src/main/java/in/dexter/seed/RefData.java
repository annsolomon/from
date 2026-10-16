package in.dexter.seed;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The versioned reference data in {@code seed/data/v1} (categories, spending model, merchants and aliases, the 40
 * MOCK_ card products with reward rules and milestones), typed and with ids.
 *
 * <p>Reference ids depend only on the data version, never on {@code --seed}: a merchant or card product has the
 * same id in every dataset, so later phases can refer to them in tests.
 */
public final class RefData {
  public static final String VERSION = "v1";
  static final String DIR = "/seed-data/" + VERSION + "/";
  static final List<String> FILES = List.of("categories.csv", "spend_model.csv", "merchants.csv",
      "merchant_aliases.csv", "issuers.csv", "card_products.csv", "reward_rules.csv", "milestones.csv");
  /** Creation time of all reference rows (ids are time-ordered, so it is also their UUIDv7 timestamp). */
  public static final Instant REF_EPOCH = Instant.parse("2025-01-01T00:00:00Z");

  public record Category(UUID id, String path, String name, String icon) {
    public String root() {
      int dot = path.indexOf('.');
      return dot < 0 ? path : path.substring(0, dot);
    }
  }

  /** How one leaf category is spent: relative frequency, amount distribution, payment mix, seasonality. */
  public record SpendModel(String path, int txnWeight, long medianPaise, int sigmaMilli, int upiBps, int cardBps,
      int festiveMilli, int salaryMilli) {}

  public record Merchant(UUID id, String slug, String name, String normalized, String category) {}

  public record Alias(String aliasNormalized, String merchantSlug, int weight) {}

  public record Issuer(UUID id, String code, String name, String type) {}

  public record Rule(UUID id, String categoryPath, String merchantSlug, String rewardType, int rateBps,
      long pointValueMilliPaise, Long capPaise, String capPeriod, long minTxnPaise, List<String> excludedPaths,
      int priority) {}

  public record Milestone(long spendThresholdPaise, String period, String benefitType, long benefitValuePaise) {}

  public record Card(UUID id, String slug, Issuer issuer, String name, String network, String tier,
      long joiningFeePaise, long annualFeePaise, long feeWaiverSpendPaise, int aprBps, int forexMarkupBps,
      int fuelSurchargeWaiverBps, Map<String, String> features, String status, List<Rule> rules,
      Milestone milestone) {}

  public final List<Category> categories;
  public final Map<String, Category> categoryByPath;
  public final List<SpendModel> spendModel;
  public final List<Merchant> merchants;
  public final Map<String, Merchant> merchantBySlug;
  public final Map<String, List<Merchant>> merchantsByCategory;
  public final List<Alias> aliases;
  public final List<Issuer> issuers;
  public final List<Card> cards;
  public final String dataSha256;

  private RefData() {
    Rng ids = new Rng(0x5EED_DA7AL).fork("ref:" + VERSION);
    long t = REF_EPOCH.toEpochMilli();

    List<Category> cats = new ArrayList<>();
    for (Map<String, String> r : Csv.read(DIR + "categories.csv")) {
      String path = Csv.req(r, "path");
      cats.add(new Category(SeedIds.v7(t, ids.fork("category:" + path)), path, Csv.req(r, "name"), Csv.req(r, "icon")));
    }
    categories = List.copyOf(cats);
    Map<String, Category> byPath = new LinkedHashMap<>();
    cats.forEach(c -> byPath.put(c.path(), c));
    categoryByPath = Collections.unmodifiableMap(byPath);

    List<SpendModel> model = new ArrayList<>();
    for (Map<String, String> r : Csv.read(DIR + "spend_model.csv")) {
      SpendModel m = new SpendModel(Csv.req(r, "category_path"), Csv.intOf(r, "txn_weight"),
          Csv.longOf(r, "median_paise"), Csv.intOf(r, "sigma_milli"), Csv.intOf(r, "upi_bps"),
          Csv.intOf(r, "card_bps"), Csv.intOf(r, "festive_milli"), Csv.intOf(r, "salary_milli"));
      require(byPath.containsKey(m.path()), "spend_model: unknown category " + m.path());
      require(m.upiBps() + m.cardBps() <= 10_000, "spend_model: upi + card > 100% for " + m.path());
      model.add(m);
    }
    spendModel = List.copyOf(model);

    List<Merchant> ms = new ArrayList<>();
    Map<String, Merchant> bySlug = new LinkedHashMap<>();
    Map<String, List<Merchant>> byCat = new LinkedHashMap<>();
    for (Map<String, String> r : Csv.read(DIR + "merchants.csv")) {
      String slug = Csv.req(r, "slug");
      String name = Csv.req(r, "canonical_name");
      String cat = Csv.req(r, "default_category");
      require(byPath.containsKey(cat), "merchants: unknown category " + cat);
      Merchant m = new Merchant(SeedIds.v7(t, ids.fork("merchant:" + slug)), slug, name, normalize(name), cat);
      require(bySlug.put(slug, m) == null, "merchants: duplicate slug " + slug);
      ms.add(m);
      byCat.computeIfAbsent(cat, k -> new ArrayList<>()).add(m);
    }
    merchants = List.copyOf(ms);
    merchantBySlug = Collections.unmodifiableMap(bySlug);
    Map<String, List<Merchant>> frozen = new LinkedHashMap<>();
    byCat.forEach((k, v) -> frozen.put(k, List.copyOf(v)));
    merchantsByCategory = Collections.unmodifiableMap(frozen);
    for (SpendModel m : spendModel) {
      require(m.path().equals("general") || merchantsByCategory.containsKey(m.path()),
          "spend_model: no merchant for " + m.path());
    }

    List<Alias> as = new ArrayList<>();
    for (Map<String, String> r : Csv.read(DIR + "merchant_aliases.csv")) {
      Alias a = new Alias(Csv.req(r, "alias_normalized"), Csv.req(r, "merchant_slug"), Csv.intOf(r, "weight"));
      require(bySlug.containsKey(a.merchantSlug()), "merchant_aliases: unknown merchant " + a.merchantSlug());
      require(a.aliasNormalized().equals(normalize(a.aliasNormalized())), "alias not normalized: " + a.aliasNormalized());
      as.add(a);
    }
    aliases = List.copyOf(as);

    Map<String, Issuer> issuerByCode = new LinkedHashMap<>();
    for (Map<String, String> r : Csv.read(DIR + "issuers.csv")) {
      String code = Csv.req(r, "code");
      require(code.startsWith("MOCK_"), "issuers: every issuer is MOCK_: " + code);
      issuerByCode.put(code, new Issuer(SeedIds.v7(t, ids.fork("issuer:" + code)), code, Csv.req(r, "name"),
          Csv.req(r, "type")));
    }
    issuers = List.copyOf(issuerByCode.values());

    Map<String, List<Rule>> rulesBySlug = new HashMap<>();
    for (Map<String, String> r : Csv.read(DIR + "reward_rules.csv")) {
      String slug = Csv.req(r, "card_slug");
      String cat = emptyToNull(Csv.req(r, "category_path"));
      String merchant = emptyToNull(Csv.req(r, "merchant_slug"));
      require(cat == null || byPath.containsKey(cat), "reward_rules: unknown category " + cat);
      require(merchant == null || bySlug.containsKey(merchant), "reward_rules: unknown merchant " + merchant);
      String cap = emptyToNull(Csv.req(r, "cap_paise"));
      String excl = Csv.req(r, "excluded_paths");
      List<String> excluded = excl.isEmpty() ? List.of() : List.of(excl.split("\\|"));
      excluded.forEach(p -> require(byPath.containsKey(p), "reward_rules: unknown excluded path " + p));
      int priority = Csv.intOf(r, "priority");
      List<Rule> list = rulesBySlug.computeIfAbsent(slug, k -> new ArrayList<>());
      list.add(new Rule(SeedIds.v7(t, ids.fork("rule:" + slug + ":" + cat + ":" + merchant + ":" + priority)),
          cat, merchant, Csv.req(r, "reward_type"), Csv.intOf(r, "rate_bps"), Csv.longOf(r, "point_value_milli_paise"),
          cap == null ? null : Long.parseLong(cap), emptyToNull(Csv.req(r, "cap_period")),
          Csv.longOf(r, "min_txn_paise"), excluded, priority));
    }

    Map<String, Milestone> milestoneBySlug = new HashMap<>();
    for (Map<String, String> r : Csv.read(DIR + "milestones.csv")) {
      milestoneBySlug.put(Csv.req(r, "card_slug"), new Milestone(Csv.longOf(r, "spend_threshold_paise"),
          Csv.req(r, "period"), Csv.req(r, "benefit_type"), Csv.longOf(r, "benefit_value_paise")));
    }

    List<Card> cs = new ArrayList<>();
    for (Map<String, String> r : Csv.read(DIR + "card_products.csv")) {
      String slug = Csv.req(r, "slug");
      String name = Csv.req(r, "name");
      require(slug.startsWith("mock-") && name.startsWith("MOCK_"), "card_products: not marked MOCK: " + slug);
      Issuer issuer = issuerByCode.get(Csv.req(r, "issuer_code"));
      require(issuer != null, "card_products: unknown issuer for " + slug);
      List<Rule> rules = rulesBySlug.getOrDefault(slug, List.of());
      require(rules.stream().filter(x -> x.categoryPath() == null && x.merchantSlug() == null).count() == 1,
          "card_products: exactly one base rule expected for " + slug);
      long distinctScopes = rules.stream().map(x -> x.categoryPath() + "|" + x.merchantSlug()).distinct().count();
      require(distinctScopes == rules.size(), "reward_rules: overlapping scope for " + slug);
      cs.add(new Card(SeedIds.v7(t, ids.fork("card:" + slug)), slug, issuer, name, Csv.req(r, "network"),
          Csv.req(r, "tier"), Csv.longOf(r, "joining_fee_paise"), Csv.longOf(r, "annual_fee_paise"),
          Csv.longOf(r, "fee_waiver_spend_paise"), Csv.intOf(r, "apr_bps"), Csv.intOf(r, "forex_markup_bps"),
          Csv.intOf(r, "fuel_surcharge_waiver_bps"), features(Csv.req(r, "features")), Csv.req(r, "status"),
          List.copyOf(rules), milestoneBySlug.get(slug)));
    }
    require(cs.size() == 40, "card_products: expected 40 MOCK_ products, found " + cs.size());
    require(rulesBySlug.keySet().stream().allMatch(s -> cs.stream().anyMatch(c -> c.slug().equals(s))),
        "reward_rules: rule for an unknown card");
    cards = List.copyOf(cs);
    dataSha256 = sha256OfFiles();
  }

  private static final RefData INSTANCE = new RefData();

  public static RefData get() {
    return INSTANCE;
  }

  /** The resolver normalisation of MASTER_PLAN P08, minimal form: lowercase, '&' → and, non-alphanumerics → space. */
  public static String normalize(String s) {
    String x = s.toLowerCase(java.util.Locale.ROOT).replace("&", " and ").replace("'", "");
    x = x.replaceAll("[^a-z0-9]+", " ");
    return x.trim().replaceAll("\\s+", " ");
  }

  private static Map<String, String> features(String spec) {
    Map<String, String> out = new LinkedHashMap<>();
    if (spec.isEmpty()) {
      return out;
    }
    for (String kv : spec.split(";")) {
      int eq = kv.indexOf('=');
      require(eq > 0, "features: bad entry " + kv);
      out.put(kv.substring(0, eq), kv.substring(eq + 1));
    }
    return Collections.unmodifiableMap(out);
  }

  private static String emptyToNull(String s) {
    return s == null || s.isEmpty() ? null : s;
  }

  static void require(boolean ok, String message) {
    if (!ok) {
      throw new IllegalStateException("seed reference data " + VERSION + ": " + message);
    }
  }

  private static String sha256OfFiles() {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      for (String f : FILES) {
        try (InputStream in = RefData.class.getResourceAsStream(DIR + f)) {
          md.update(f.getBytes(java.nio.charset.StandardCharsets.UTF_8));
          md.update(in.readAllBytes());
        }
      }
      return HexFormat.of().formatHex(md.digest());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
