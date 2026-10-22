package in.dexter.seed;

import in.dexter.seed.CopyWriter.Raw;
import in.dexter.seed.RefData.Card;
import in.dexter.seed.RefData.Merchant;
import in.dexter.seed.RefData.SpendModel;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Generates one dataset (MASTER_PLAN 11.1): COPY files per service database, {@code manifest.tsv} (what the loader
 * reads) and {@code manifest.json} (checksums, for people and the determinism check), plus the trail fixtures when
 * asked.
 *
 * <p>Determinism: every value comes from {@link Rng} streams forked by stable labels, timestamps are fixed (the
 * spending window ends 2026-09-30 IST, never "now"), and nothing iterates a hash-ordered collection. Same
 * {@code --seed} + size + reference data + passwords → the same bytes, so the same {@code checksum}.
 *
 * <p>Accounts are generated one at a time and streamed out, so memory stays flat up to {@code large}.
 */
public final class Generator {
  /** P05: the demo admin's standing roles; it reaches SUPER_ADMIN only through break-glass (break_glass_eligible). */
  static final java.util.List<String> ADMIN_STANDING_ROLES = java.util.List.of("SUPPORT", "CATALOG_EDITOR", "FINANCE");

  public static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
  /** Spending window: 2025-10-01 .. 2026-09-30 in IST (365 days). */
  public static final LocalDate WINDOW_START = LocalDate.of(2025, 10, 1);
  public static final int WINDOW_DAYS = 365;
  /** Navratri, Dussehra, Dhanteras and Diwali 2025 (Diwali was 2025-10-20/21). */
  static final LocalDate FESTIVE_FROM = LocalDate.of(2025, 10, 1);
  static final LocalDate FESTIVE_TO = LocalDate.of(2025, 10, 24);
  static final Instant BULK_CREATED_FROM = Instant.parse("2023-01-01T00:00:00Z");
  static final Instant BULK_CREATED_TO = Instant.parse("2025-09-15T00:00:00Z");
  static final long MAX_EXPENSE_PAISE = 50_00_000_00L; // ₹50 lakh

  public record Config(long seed, Size size, Path out, Path fixtures, Map<String, String> passwords) {
    public Config {
      Objects.requireNonNull(size);
      Objects.requireNonNull(out);
      passwords = Map.copyOf(passwords);
    }
  }

  /** What one run produced. {@code checksum} covers every table file and fixture. */
  public record Result(String checksum, Map<String, Long> rows, Duration elapsed) {}

  private final Config cfg;
  private final RefData ref = RefData.get();
  private final Rng root;
  private final Map<Table, CopyWriter> writers = new LinkedHashMap<>();
  private final Map<String, String> tableSha = new LinkedHashMap<>();
  private final Map<String, Long> tableRows = new LinkedHashMap<>();
  private final long[][] dayCum;
  private final Map<String, long[]> hourCum = new HashMap<>();

  // fixtures (demo accounts only)
  private final List<Map<String, Object>> fxExpenses = new ArrayList<>();
  private final List<Map<String, Object>> fxUserCards = new ArrayList<>();
  private final List<Map<String, Object>> fxPersonas = new ArrayList<>();

  public Generator(Config cfg) {
    this.cfg = cfg;
    this.root = new Rng(cfg.seed());
    this.dayCum = new long[ref.spendModel.size()][];
    for (int m = 0; m < ref.spendModel.size(); m++) {
      dayCum[m] = dayWeights(ref.spendModel.get(m));
    }
  }

  // ======================================================================== run

  public Result run() {
    long t0 = System.nanoTime();
    try {
      Files.createDirectories(cfg.out());
      for (Table t : Tables.ALL) {
        writers.put(t, new CopyWriter(t, cfg.out().resolve(t.file())));
      }
      writeCatalog();
      writeLedgerReference();
      List<Acct> accounts = writeIdentity();
      writePortfolio(accounts);
      writeExpenses(accounts);
      for (CopyWriter w : writers.values()) {
        tableSha.put(w.table().file(), w.finish());
        tableRows.put(w.table().service() + "." + w.table().name(), w.rows());
      }
      Map<String, String> fixtureSha = cfg.fixtures() == null ? Map.of() : writeFixtures();
      String checksum = checksum(fixtureSha);
      writeManifests(checksum, fixtureSha);
      return new Result(checksum, Map.copyOf(tableRows), Duration.ofNanos(System.nanoTime() - t0));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } finally {
      writers.values().forEach(CopyWriter::close);
    }
  }

  private CopyWriter w(Table t) {
    return writers.get(t);
  }

  // ======================================================================== reference data

  private void writeCatalog() {
    Instant t = RefData.REF_EPOCH;
    for (RefData.Issuer i : ref.issuers) {
      w(Tables.ISSUERS).row(i.id(), i.code(), i.name(), i.type());
    }
    for (Card c : ref.cards) {
      Map<String, Object> snapshot = cardSnapshot(c);
      String snapshotJson = Json.write(snapshot).strip();
      w(Tables.CARD_PRODUCTS).row(c.id(), c.issuer().id(), c.slug(), c.name(), c.network(), c.tier(),
          c.joiningFeePaise(), c.annualFeePaise(), c.feeWaiverSpendPaise(), c.aprBps(), c.forexMarkupBps(),
          c.fuelSurchargeWaiverBps(), new Raw(Json.write(featureJson(c)).strip()), c.status(), 1,
          new Raw("\\x" + sha256Hex(snapshotJson)), t, t, 0);
      w(Tables.CARD_PRODUCT_VERSIONS).row(c.id(), 1, new Raw(snapshotJson), t, null);
      if ("published".equals(c.status()) || "retired".equals(c.status())) {
        w(Tables.CATALOG_PRODUCTS_PROJ).row(c.id(), c.slug(), c.name(), c.issuer().code(), c.network(), c.tier(),
            c.annualFeePaise(), c.status(), 1, new Raw(snapshotJson), 0L, t);
      }
      for (RefData.Rule r : c.rules()) {
        UUID merchantId = r.merchantSlug() == null ? null : ref.merchantBySlug.get(r.merchantSlug()).id();
        w(Tables.REWARD_RULES).row(r.id(), c.id(), 1, r.categoryPath(), merchantId, r.rewardType(), r.rateBps(),
            r.pointValueMilliPaise(), r.capPaise(), r.capPeriod(), r.minTxnPaise(), r.excludedPaths(),
            new Raw("[" + pgTime(t) + ",)"), r.priority());
      }
      if (c.milestone() != null) {
        RefData.Milestone m = c.milestone();
        w(Tables.MILESTONES).row(c.id(), m.spendThresholdPaise(), m.period(), m.benefitType(), m.benefitValuePaise());
      }
    }
  }

  private void writeLedgerReference() {
    for (RefData.Category c : ref.categories) {
      w(Tables.CATEGORIES).row(c.id(), null, c.path(), c.name(), c.icon(), true);
    }
    for (Merchant m : ref.merchants) {
      w(Tables.MERCHANTS).row(m.id(), m.name(), m.normalized(), m.category(), null, "active");
    }
    for (RefData.Alias a : ref.aliases) {
      w(Tables.MERCHANT_ALIASES).row(a.aliasNormalized(), ref.merchantBySlug.get(a.merchantSlug()).id(), a.weight());
    }
  }

  // ======================================================================== identity

  /** One account's generation state (kept for all accounts: ids and cards are needed by later tables). */
  static final class Acct {
    final String key;
    final Persona persona;
    final Rng rng;
    UUID id;
    Instant createdAt;
    double income;
    String city;
    final List<UserCard> cards = new ArrayList<>();
    long expenseCount;

    Acct(String key, Persona persona, Rng rng) {
      this.key = key;
      this.persona = persona;
      this.rng = rng;
    }
  }

  record UserCard(UUID id, Card product, Instant createdAt) {}

  private List<Acct> writeIdentity() {
    String demoBulkPw = password(Persona.BULK_PASSWORD_ENV);
    String bulkHash = cfg.size().bulkAccounts() == 0 ? null
        : Passwords.argon2id(demoBulkPw, root.fork("salt:bulk"));
    List<Acct> out = new ArrayList<>(cfg.size().accounts());
    for (Persona p : Persona.USERS) {
      Acct a = new Acct(p.key(), p, root.fork("account:" + p.email()));
      a.createdAt = p.createdAt();
      a.id = SeedIds.v7(a.createdAt.toEpochMilli(), a.rng.fork("id"));
      a.income = p == Persona.ASHA ? 1.6 : 0.8;
      a.city = p == Persona.ASHA ? "BANGALORE" : "CHENNAI";
      String hash = Passwords.argon2id(password(p.passwordEnv()), root.fork("salt:" + p.email()));
      Instant verified = a.createdAt.plus(Duration.ofMinutes(7));
      w(Tables.ACCOUNTS).row(a.id, p.email(), hash, "active", verified, ZONE.getId(), p.tier(), a.createdAt,
          a.createdAt, 0);
      Map<String, Object> fx = new LinkedHashMap<>();
      fx.put("key", p.key());
      fx.put("accountId", a.id);
      fx.put("email", p.email());
      fx.put("displayName", p.displayName());
      fx.put("role", p.role());
      fx.put("tz", ZONE.getId());
      fx.put("tier", p.tier());
      fx.put("createdAt", a.createdAt);
      fxPersonas.add(fx);
      out.add(a);
    }
    Persona admin = Persona.ADMIN;
    UUID adminId = SeedIds.v7(admin.createdAt().toEpochMilli(), root.fork("admin:" + admin.email()));
    w(Tables.ADMINS).row(adminId, admin.email(), Passwords.argon2id(password(admin.passwordEnv()),
        root.fork("salt:" + admin.email())), "active", true, true, admin.createdAt(), admin.createdAt(), 0);
    for (String role : ADMIN_STANDING_ROLES) {
      w(Tables.ADMIN_ROLES).row(adminId, role);
    }
    Map<String, Object> afx = new LinkedHashMap<>();
    afx.put("key", admin.key());
    afx.put("adminId", adminId);
    afx.put("email", admin.email());
    afx.put("displayName", admin.displayName());
    afx.put("role", admin.role());
    afx.put("tz", ZONE.getId());
    afx.put("tier", null);
    afx.put("createdAt", admin.createdAt());
    fxPersonas.add(afx);

    long span = BULK_CREATED_TO.getEpochSecond() - BULK_CREATED_FROM.getEpochSecond();
    for (int i = 1; i <= cfg.size().bulkAccounts(); i++) {
      String key = String.format("bulk:%07d", i);
      Acct a = new Acct(key, null, root.fork("account:" + key));
      Rng r = a.rng.fork("identity");
      a.createdAt = BULK_CREATED_FROM.plusSeconds(Math.floorMod(r.nextLong(), span));
      a.id = SeedIds.v7(a.createdAt.toEpochMilli(), a.rng.fork("id"));
      a.income = clamp(a.rng.fork("income").logNormal(1.0, 0.45), 0.3, 4.0);
      a.city = CITIES.get(r.nextInt(CITIES.size()));
      int s = r.nextInt(100);
      String status = s < 96 ? "active" : s < 98 ? "pending_verification" : s < 99 ? "locked" : "disabled";
      Instant verified = status.equals("pending_verification") ? null
          : a.createdAt.plus(Duration.ofMinutes(r.between(2, 600)));
      int t = r.nextInt(100);
      String tier = t < 85 ? "FREE" : t < 97 ? "PREMIUM" : "VIP";
      w(Tables.ACCOUNTS).row(a.id, String.format("u%07d@seed.test", i), bulkHash, status, verified, ZONE.getId(),
          tier, a.createdAt, a.createdAt, 0);
      out.add(a);
    }
    return out;
  }

  private String password(String env) {
    String v = cfg.passwords().get(env);
    if (v == null || v.isEmpty()) {
      throw new IllegalArgumentException("password " + env + " not provided (set it in .env)");
    }
    return v;
  }

  // ======================================================================== portfolio

  private void writePortfolio(List<Acct> accounts) {
    Map<String, Card> bySlug = new HashMap<>();
    ref.cards.forEach(c -> bySlug.put(c.slug(), c));
    for (Acct a : accounts) {
      if (a.persona != null) {
        Rng r = a.rng.fork("cards");
        for (String slug : a.persona.cardSlugs()) {
          addCard(a, Objects.requireNonNull(bySlug.get(slug), slug), r);
        }
      }
    }
    int[] counts = bulkCardCounts();
    for (int i = 0; i < counts.length; i++) {
      Acct a = accounts.get(Size.DEMO_ACCOUNTS + i);
      Rng r = a.rng.fork("cards");
      long[] cum = productWeights(a.income);
      boolean[] taken = new boolean[ref.cards.size()];
      for (int k = 0; k < counts[i]; k++) {
        int idx;
        do {
          idx = r.weighted(cum);
        } while (taken[idx]);
        taken[idx] = true;
        addCard(a, ref.cards.get(idx), r);
      }
    }
  }

  /** Exactly {@code size.bulkCards()} cards over the bulk accounts, 0..6 each. */
  private int[] bulkCardCounts() {
    int n = cfg.size().bulkAccounts();
    int[] k = new int[n];
    if (n == 0) {
      return k;
    }
    Rng r = root.fork("card-counts");
    int[] pmf = {1500, 2000, 2000, 1800, 1200, 900, 600}; // P(k) in bps, k = 0..6
    long sum = 0;
    for (int i = 0; i < n; i++) {
      int x = r.nextInt(10_000);
      int c = 0;
      while (x >= pmf[c]) {
        x -= pmf[c];
        c++;
      }
      k[i] = c;
      sum += c;
    }
    long diff = cfg.size().bulkCards() - sum;
    while (diff != 0) {
      int i = r.nextInt(n);
      if (diff > 0 && k[i] < Size.MAX_CARDS_PER_ACCOUNT) {
        k[i]++;
        diff--;
      } else if (diff < 0 && k[i] > 0) {
        k[i]--;
        diff++;
      }
    }
    return k;
  }

  private long[] productWeights(double income) {
    long[] cum = new long[ref.cards.size()];
    long total = 0;
    for (int i = 0; i < cum.length; i++) {
      long w = switch (ref.cards.get(i).tier()) {
        case "entry" -> 5_000;
        case "mid" -> 3_000;
        case "premium" -> Math.round(1_500 * income);
        default -> Math.round(600 * income * income);
      };
      total += Math.max(1, w);
      cum[i] = total;
    }
    return cum;
  }

  private void addCard(Acct a, Card c, Rng r) {
    Instant latest = WINDOW_START.minusDays(1).atStartOfDay(ZONE).toInstant();
    Instant created = a.createdAt.plus(Duration.ofMinutes(r.between(10, 30 * 24 * 60)));
    if (created.isAfter(latest)) {
      created = latest.minus(Duration.ofMinutes(r.between(1, 600)));
    }
    if (created.isBefore(a.createdAt)) {
      created = a.createdAt;
    }
    UUID id = SeedIds.v7(created.toEpochMilli(), r);
    String last4 = String.format("%04d", r.nextInt(10_000));
    String nickname = c.name().substring("MOCK_".length());
    int statementDay = r.between(1, 28);
    int dueOffset = r.between(18, 21);
    long[] range = switch (c.tier()) {
      case "entry" -> new long[] {50_000, 150_000};
      case "mid" -> new long[] {100_000, 300_000};
      case "premium" -> new long[] {300_000, 800_000};
      default -> new long[] {800_000, 2_500_000};
    };
    long rupees = range[0] + Math.floorMod(r.nextLong(), range[1] - range[0] + 1);
    rupees = Math.max(25_000, Math.round(rupees * Math.min(a.income, 2.0) / 5_000.0) * 5_000);
    long limitPaise = rupees * 100;
    LocalDate createdDay = LocalDate.ofInstant(a.createdAt, ZONE);
    LocalDate earliest = LocalDate.of(2019, 1, 1);
    long days = Math.max(1, createdDay.toEpochDay() - earliest.toEpochDay());
    LocalDate openedOn = earliest.plusDays(Math.floorMod(r.nextLong(), days));
    w(Tables.USER_CARDS).row(id, a.id, c.id(), nickname, last4, c.network(), (short) statementDay, (short) dueOffset,
        limitPaise, openedOn, "active", created, created, 0);
    a.cards.add(new UserCard(id, c, created));
    if (a.persona != null) {
      Map<String, Object> fx = new LinkedHashMap<>();
      fx.put("id", id);
      fx.put("accountId", a.id);
      fx.put("cardProductId", c.id());
      fx.put("cardProductSlug", c.slug());
      fx.put("nickname", nickname);
      fx.put("last4", last4);
      fx.put("network", c.network());
      fx.put("statementDay", statementDay);
      fx.put("dueOffsetDays", dueOffset);
      fx.put("creditLimitPaise", limitPaise);
      fx.put("openedOn", openedOn);
      fx.put("status", "active");
      fx.put("version", 0);
      fxUserCards.add(fx);
    }
  }

  // ======================================================================== ledger: expenses + daily totals

  private void writeExpenses(List<Acct> accounts) {
    for (int i = 0; i < Size.DEMO_ACCOUNTS; i++) {
      accounts.get(i).expenseCount = Size.DEMO_EXPENSES_EACH;
    }
    allocateBulkExpenses(accounts.subList(Size.DEMO_ACCOUNTS, accounts.size()));
    for (Acct a : accounts) {
      writeAccountExpenses(a);
    }
  }

  /** Largest-remainder split of the bulk total over log-normal activity weights: the total is exact. */
  private void allocateBulkExpenses(List<Acct> bulk) {
    int n = bulk.size();
    if (n == 0) {
      return;
    }
    double[] w = new double[n];
    double total = 0;
    for (int i = 0; i < n; i++) {
      w[i] = bulk.get(i).rng.fork("activity").logNormal(1.0, 0.6);
      total += w[i];
    }
    long target = cfg.size().bulkExpenses();
    long assigned = 0;
    double[] frac = new double[n];
    for (int i = 0; i < n; i++) {
      double q = target * (w[i] / total);
      long f = (long) Math.floor(q);
      bulk.get(i).expenseCount = f;
      frac[i] = q - f;
      assigned += f;
    }
    Integer[] order = new Integer[n];
    for (int i = 0; i < n; i++) {
      order[i] = i;
    }
    Arrays.sort(order, (x, y) -> {
      int c = Double.compare(frac[y], frac[x]);
      return c != 0 ? c : Integer.compare(x, y);
    });
    for (long r = target - assigned, j = 0; r > 0; r--, j++) {
      bulk.get(order[(int) (j % n)]).expenseCount++;
    }
  }

  private void writeAccountExpenses(Acct a) {
    if (a.expenseCount == 0) {
      return;
    }
    Rng x = a.rng.fork("expenses");
    long[] catCum = categoryPrefs(a.rng.fork("prefs"));
    long rentPaise = Math.max(5_000_00L, Math.round(a.rng.fork("rent").logNormal(2_200_000 * a.income, 0.3)
        / 50_000.0) * 50_000);
    Map<String, Merchant[]> favourites = new HashMap<>();
    TreeMap<String, long[]> daily = new TreeMap<>();
    String refPrefix = a.id.toString().substring(0, 8);
    for (long j = 0; j < a.expenseCount; j++) {
      int m = x.weighted(catCum);
      SpendModel sm = ref.spendModel.get(m);
      LocalDate day = WINDOW_START.plusDays(x.weighted(dayCum[m]));
      String root = rootOf(sm.path());
      int hour = x.weighted(hourCum.computeIfAbsent(root, Generator::hourWeights));
      Instant occurred = day.atTime(hour, x.nextInt(60), x.nextInt(60)).atZone(ZONE).toInstant()
          .plusMillis(x.nextInt(1000));
      long amount = amountFor(sm, a, rentPaise, x);

      String method = paymentMethod(sm, a, x);
      UUID cardId = null;
      if (method.equals("card")) {
        int ci = a.cards.size() == 1 || x.chance(5_500) ? 0 : x.nextInt(a.cards.size());
        cardId = a.cards.get(ci).id();
      }

      Merchant merchant = null;
      String display;
      if (sm.path().equals("general")) {
        display = LOCAL_PAYEES.get(x.nextInt(LOCAL_PAYEES.size()));
      } else {
        merchant = pickMerchant(sm.path(), a, favourites, x);
        display = merchant.name();
      }
      String raw = rawDescriptor(display, method, a.city, x);

      String source = x.chance(1_500) ? "import" : "manual";
      String catSource;
      int confidence;
      if (merchant == null) {
        catSource = "resolver";
        confidence = x.between(0, 400);
      } else if (source.equals("import")) {
        catSource = "import";
        confidence = x.between(650, 1000);
      } else if (x.chance(800)) {
        catSource = "user";
        confidence = 1000;
      } else {
        catSource = "resolver";
        confidence = x.between(780, 1000);
      }
      String note = x.chance(700) ? note(root, day, x) : null;
      List<String> tags = x.chance(400) ? TAG_SETS.get(x.nextInt(TAG_SETS.size())) : null;
      String clientRef = source.equals("import") ? "imp-" + refPrefix + "-" + j : null;

      Instant created = source.equals("import")
          ? occurred.plus(Duration.ofSeconds(x.between(86_400, 10 * 86_400)))
          : occurred.plus(Duration.ofSeconds(x.between(60, 7_200)));
      UUID id = SeedIds.v7(created.toEpochMilli(), x);
      int version = 0;
      Instant updated = created;
      Instant deleted = null;
      if (x.chance(300)) {
        version = x.between(1, 2);
        updated = created.plus(Duration.ofSeconds(x.between(3_600, 30 * 86_400)));
      }
      if (x.chance(100)) {
        deleted = updated.plus(Duration.ofSeconds(x.between(600, 48 * 3_600)));
        updated = deleted;
        version++;
      }
      w(Tables.EXPENSES).row(id, a.id, occurred, amount, "INR", raw, display, merchant == null ? null : merchant.id(),
          sm.path(), catSource, (short) confidence, method, cardId, note, tags, clientRef, source, created, updated,
          version, deleted);
      if (deleted == null) {
        long[] agg = daily.computeIfAbsent(day + "|" + root, k -> new long[2]);
        agg[0] += amount;
        agg[1]++;
        if (a.persona != null) {
          Map<String, Object> fx = new LinkedHashMap<>();
          fx.put("id", id);
          fx.put("accountId", a.id);
          fx.put("occurredAt", occurred);
          fx.put("amountPaise", amount);
          fx.put("currency", "INR");
          fx.put("merchantDisplay", display);
          fx.put("merchantId", merchant == null ? null : merchant.id());
          fx.put("categoryPath", sm.path());
          fx.put("categorySource", catSource);
          fx.put("resolutionConfidence", confidence);
          fx.put("paymentMethod", method);
          fx.put("userCardId", cardId);
          fx.put("note", note);
          fx.put("tags", tags == null ? List.of() : tags);
          fx.put("source", source);
          fx.put("version", version);
          fx.put("createdAt", created);
          fxExpenses.add(fx);
        }
      }
    }
    for (Map.Entry<String, long[]> e : daily.entrySet()) {
      int bar = e.getKey().indexOf('|');
      w(Tables.EXPENSE_DAILY_TOTALS).row(a.id, LocalDate.parse(e.getKey().substring(0, bar)),
          e.getKey().substring(bar + 1), e.getValue()[0], (int) e.getValue()[1]);
    }
  }

  /** Per-account category weights: txn weight × a log-normal taste, with some categories absent for many people. */
  private long[] categoryPrefs(Rng r) {
    long[] cum = new long[ref.spendModel.size()];
    long total = 0;
    for (int i = 0; i < cum.length; i++) {
      SpendModel sm = ref.spendModel.get(i);
      int absentBps = switch (sm.path()) {
        case "housing.rent" -> 6_500;
        case "education.fees" -> 8_500;
        case "travel.air" -> 4_500;
        case "travel.hotel" -> 5_000;
        case "fuel.petrol" -> 2_500;
        case "fuel.ev" -> 9_000;
        case "finance.investments" -> 5_000;
        default -> 0;
      };
      long w = r.chance(absentBps) ? 0 : Math.round(sm.txnWeight() * 1000 * r.logNormal(1.0, 0.5));
      total += w;
      cum[i] = total;
    }
    if (total == 0) {
      cum[cum.length - 1] = 1; // degenerate taste: everything in "general"
    }
    return cum;
  }

  private long amountFor(SpendModel sm, Acct a, long rentPaise, Rng x) {
    long rupees = switch (sm.path()) {
      case "utilities.mobile" -> pickOf(x, 199, 239, 299, 349, 399, 479, 599, 719, 839, 999);
      case "entertainment.ott" -> pickOf(x, 149, 199, 299, 499, 649, 899, 1499);
      case "utilities.gas" -> pickOf(x, 853, 903, 918, 1103);
      case "utilities.broadband" -> pickOf(x, 499, 599, 799, 999, 1178, 1499);
      default -> -1;
    };
    if (rupees > 0) {
      return rupees * 100;
    }
    if (sm.path().equals("housing.rent")) {
      return rentPaise;
    }
    double median = sm.medianPaise() * StrictMath.pow(a.income, 0.6);
    long p = Math.round(x.logNormal(median, sm.sigmaMilli() / 1000.0));
    p = Math.min(MAX_EXPENSE_PAISE, Math.max(100, p));
    if (sm.path().equals("fuel.petrol") && x.chance(6_000)) {
      return Math.max(10_000, Math.round(p / 10_000.0) * 10_000); // "₹500 of petrol"
    }
    if (x.chance(7_000)) {
      return Math.max(100, Math.round(p / 100.0) * 100); // whole rupees (most UPI/card amounts)
    }
    return p;
  }

  private static long pickOf(Rng x, long... v) {
    return v[x.nextInt(v.length)];
  }

  private String paymentMethod(SpendModel sm, Acct a, Rng x) {
    int r = x.nextInt(10_000);
    if (r < sm.upiBps()) {
      return "upi";
    }
    if (r < sm.upiBps() + sm.cardBps()) {
      return a.cards.isEmpty() ? "upi" : "card";
    }
    if (sm.path().equals("finance.investments")) {
      return "netbanking";
    }
    return switch (x.nextInt(4)) {
      case 0, 1 -> "cash";
      case 2 -> "netbanking";
      default -> "wallet";
    };
  }

  private Merchant pickMerchant(String path, Acct a, Map<String, Merchant[]> favourites, Rng x) {
    List<Merchant> all = ref.merchantsByCategory.get(path);
    Merchant[] fav = favourites.computeIfAbsent(path, p -> {
      Rng r = a.rng.fork("fav:" + p);
      int n = Math.min(3, all.size());
      List<Merchant> pool = new ArrayList<>(all);
      Merchant[] out = new Merchant[n];
      for (int i = 0; i < n; i++) {
        out[i] = pool.remove(r.nextInt(pool.size()));
      }
      return out;
    });
    if (x.chance(7_500)) {
      int r = x.nextInt(6); // weights 3:2:1
      int i = r < 3 ? 0 : r < 5 ? 1 : 2;
      return fav[Math.min(i, fav.length - 1)];
    }
    return all.get(x.nextInt(all.size()));
  }

  /** What a bank statement or UPI app would show (input for the P08 resolver). */
  static String rawDescriptor(String display, String method, String city, Rng x) {
    String upper = display.toUpperCase(java.util.Locale.ROOT);
    String compact = upper.replaceAll("[^A-Z0-9]", "");
    if (compact.length() > 20) {
      compact = compact.substring(0, 20);
    }
    return switch (method) {
      case "upi" -> "UPI/" + digits(x, 12) + "/" + compact + "/" + compact.toLowerCase(java.util.Locale.ROOT)
          + digits(x, 2) + "@" + UPI_HANDLES.get(x.nextInt(UPI_HANDLES.size()));
      case "card" -> switch (x.nextInt(3)) {
        case 0 -> upper + " " + city;
        case 1 -> "POS " + digits(x, 6) + " " + upper;
        default -> compact + "*" + digits(x, 8);
      };
      case "netbanking" -> "NB/" + compact + "/" + digits(x, 10);
      case "wallet" -> "WALLET*" + compact;
      default -> display;
    };
  }

  private static String digits(Rng x, int n) {
    StringBuilder sb = new StringBuilder(n);
    for (int i = 0; i < n; i++) {
      sb.append((char) ('0' + x.nextInt(10)));
    }
    return sb.toString();
  }

  private static String note(String root, LocalDate day, Rng x) {
    boolean festive = !day.isBefore(FESTIVE_FROM) && !day.isAfter(FESTIVE_TO);
    List<String> pool = festive && (root.equals("shopping") || root.equals("gifts") || root.equals("food"))
        ? NOTES_FESTIVE : NOTES.getOrDefault(root, NOTES_DEFAULT);
    return pool.get(x.nextInt(pool.size()));
  }

  // ======================================================================== seasonality

  private static long[] dayWeights(SpendModel sm) {
    String root = rootOf(sm.path());
    long[] cum = new long[WINDOW_DAYS];
    long total = 0;
    for (int d = 0; d < WINDOW_DAYS; d++) {
      LocalDate date = WINDOW_START.plusDays(d);
      long w = 1_000;
      if (date.getDayOfMonth() <= 7) {
        w = w * sm.salaryMilli() / 1_000; // salary lands on the 1st: bills and shopping cluster in week one
      }
      if (!date.isBefore(FESTIVE_FROM) && !date.isAfter(FESTIVE_TO)) {
        w = w * sm.festiveMilli() / 1_000;
      }
      boolean weekend = date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY;
      if (weekend) {
        w = switch (root) {
          case "utilities", "finance", "housing", "education" -> w * 600 / 1_000;
          case "food", "entertainment", "travel", "shopping" -> w * 1_250 / 1_000;
          default -> w;
        };
      }
      total += w;
      cum[d] = total;
    }
    return cum;
  }

  private static long[] hourWeights(String root) {
    int[] w = switch (root) {
      case "food" -> new int[] {0, 0, 0, 0, 0, 0, 1, 3, 6, 6, 5, 6, 10, 12, 9, 5, 5, 6, 8, 11, 12, 10, 5, 2};
      case "travel" -> new int[] {1, 1, 0, 0, 1, 2, 4, 6, 7, 6, 5, 5, 5, 5, 5, 5, 6, 7, 7, 6, 5, 4, 3, 2};
      case "utilities", "finance", "housing", "education" ->
          new int[] {0, 0, 0, 0, 0, 0, 0, 1, 3, 6, 8, 9, 9, 8, 8, 8, 7, 6, 5, 4, 3, 2, 1, 0};
      default -> new int[] {0, 0, 0, 0, 0, 0, 1, 2, 4, 6, 7, 8, 8, 8, 8, 7, 7, 7, 8, 8, 7, 5, 3, 1};
    };
    long[] cum = new long[24];
    long t = 0;
    for (int h = 0; h < 24; h++) {
      t += w[h];
      cum[h] = t;
    }
    return cum;
  }

  static String rootOf(String path) {
    int dot = path.indexOf('.');
    return dot < 0 ? path : path.substring(0, dot);
  }

  // ======================================================================== catalog snapshots

  private Map<String, Object> cardSnapshot(Card c) {
    Map<String, Object> s = new LinkedHashMap<>();
    s.put("id", c.id());
    s.put("slug", c.slug());
    s.put("name", c.name());
    s.put("issuerCode", c.issuer().code());
    s.put("issuerName", c.issuer().name());
    s.put("network", c.network());
    s.put("tier", c.tier());
    s.put("joiningFeePaise", c.joiningFeePaise());
    s.put("annualFeePaise", c.annualFeePaise());
    s.put("feeWaiverSpendPaise", c.feeWaiverSpendPaise());
    s.put("aprBps", c.aprBps());
    s.put("forexMarkupBps", c.forexMarkupBps());
    s.put("fuelSurchargeWaiverBps", c.fuelSurchargeWaiverBps());
    s.put("features", featureJson(c));
    List<Map<String, Object>> rules = new ArrayList<>();
    for (RefData.Rule r : c.rules()) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("id", r.id());
      m.put("categoryPath", r.categoryPath());
      m.put("merchantSlug", r.merchantSlug());
      m.put("rewardType", r.rewardType());
      m.put("rateBps", r.rateBps());
      m.put("pointValueMilliPaise", r.pointValueMilliPaise());
      m.put("capPaise", r.capPaise());
      m.put("capPeriod", r.capPeriod());
      m.put("minTxnPaise", r.minTxnPaise());
      m.put("excludedPaths", r.excludedPaths());
      m.put("priority", r.priority());
      rules.add(m);
    }
    s.put("rules", rules);
    if (c.milestone() != null) {
      Map<String, Object> ms = new LinkedHashMap<>();
      ms.put("spendThresholdPaise", c.milestone().spendThresholdPaise());
      ms.put("period", c.milestone().period());
      ms.put("benefitType", c.milestone().benefitType());
      ms.put("benefitValuePaise", c.milestone().benefitValuePaise());
      s.put("milestone", ms);
    } else {
      s.put("milestone", null);
    }
    return s;
  }

  private static Map<String, Object> featureJson(Card c) {
    Map<String, Object> out = new LinkedHashMap<>();
    c.features().forEach((k, v) -> {
      if (v.equals("true") || v.equals("false")) {
        out.put(k, Boolean.parseBoolean(v));
      } else if (v.matches("-?[0-9]+")) {
        out.put(k, Long.parseLong(v));
      } else {
        out.put(k, v);
      }
    });
    return out;
  }

  // ======================================================================== fixtures + manifest

  private Map<String, String> writeFixtures() throws IOException {
    Path dir = cfg.fixtures();
    Files.createDirectories(dir);
    Map<String, String> sha = new LinkedHashMap<>();
    // newest first, the order the API pages in (occurred_at DESC, id DESC; id order = unsigned bytes = hex text)
    List<Map<String, Object>> expenses = new ArrayList<>(fxExpenses);
    expenses.sort((p, q) -> {
      int c = ((Instant) q.get("occurredAt")).compareTo((Instant) p.get("occurredAt"));
      return c != 0 ? c : q.get("id").toString().compareTo(p.get("id").toString());
    });
    List<Map<String, Object>> categories = new ArrayList<>();
    for (RefData.Category c : ref.categories) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("id", c.id());
      m.put("path", c.path());
      m.put("name", c.name());
      m.put("icon", c.icon());
      m.put("isSystem", true);
      categories.add(m);
    }
    Map<String, List<String>> aliasesBySlug = new LinkedHashMap<>();
    ref.aliases.forEach(a -> aliasesBySlug.computeIfAbsent(a.merchantSlug(), k -> new ArrayList<>())
        .add(a.aliasNormalized()));
    List<Map<String, Object>> merchants = new ArrayList<>();
    for (Merchant mr : ref.merchants) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("id", mr.id());
      m.put("slug", mr.slug());
      m.put("name", mr.name());
      m.put("defaultCategory", mr.category());
      m.put("aliases", aliasesBySlug.getOrDefault(mr.slug(), List.of()));
      merchants.add(m);
    }
    List<Map<String, Object>> cards = new ArrayList<>();
    ref.cards.forEach(c -> cards.add(cardSnapshot(c)));

    sha.put("personas.json", fixture(dir, "personas.json", Map.of("data", fxPersonas)));
    sha.put("expenses.json", fixture(dir, "expenses.json", Map.of("data", expenses)));
    sha.put("user-cards.json", fixture(dir, "user-cards.json", Map.of("data", fxUserCards)));
    sha.put("categories.json", fixture(dir, "categories.json", Map.of("data", categories)));
    sha.put("merchants.json", fixture(dir, "merchants.json", Map.of("data", merchants)));
    sha.put("catalog-cards.json", fixture(dir, "catalog-cards.json", Map.of("data", cards)));
    Map<String, Object> info = new LinkedHashMap<>();
    info.put("generator", "dexter-seed (seed/, MASTER_PLAN 11.1, ADR-0008)");
    info.put("seed", cfg.seed());
    info.put("dataVersion", RefData.VERSION);
    info.put("windowFrom", WINDOW_START);
    info.put("windowDays", WINDOW_DAYS);
    info.put("zone", ZONE.getId());
    info.put("note", "Demo accounts only (asha, ravi, admin). Regenerate with: ./scripts/seed.sh fixtures");
    sha.put("seed-info.json", fixture(dir, "seed-info.json", info));
    return sha;
  }

  private static String fixture(Path dir, String name, Object content) throws IOException {
    byte[] bytes = Json.write(content).getBytes(StandardCharsets.UTF_8);
    Files.write(dir.resolve(name), bytes);
    return sha256Hex(bytes);
  }

  private String checksum(Map<String, String> fixtureSha) {
    StringBuilder sb = new StringBuilder();
    for (Table t : Tables.ALL) {
      sb.append(t.file()).append('\t').append(tableRows.get(t.service() + "." + t.name())).append('\t')
          .append(tableSha.get(t.file())).append('\n');
    }
    fixtureSha.forEach((f, s) -> sb.append("fixtures/").append(f).append('\t').append(s).append('\n'));
    return sha256Hex(sb.toString());
  }

  private void writeManifests(String checksum, Map<String, String> fixtureSha) throws IOException {
    StringBuilder tsv = new StringBuilder("# service\ttable\tphase\tfile\trows\tsha256\tcolumns\n");
    List<Map<String, Object>> tables = new ArrayList<>();
    for (Table t : Tables.ALL) {
      long rows = tableRows.get(t.service() + "." + t.name());
      String sha = tableSha.get(t.file());
      tsv.append(t.service()).append('\t').append(t.name()).append('\t').append(t.phase()).append('\t')
          .append(t.file()).append('\t').append(rows).append('\t').append(sha).append('\t')
          .append(String.join(",", t.columnNames())).append('\n');
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("service", t.service());
      m.put("table", t.name());
      m.put("createdByPhase", t.phase());
      m.put("file", t.file());
      m.put("rows", rows);
      m.put("sha256", sha);
      tables.add(m);
    }
    Files.writeString(cfg.out().resolve("manifest.tsv"), tsv.toString());
    Map<String, Object> size = new LinkedHashMap<>();
    size.put("name", cfg.size().name());
    size.put("accounts", cfg.size().accounts());
    size.put("expenses", cfg.size().expenses());
    size.put("userCards", cfg.size().userCards());
    Map<String, Object> j = new LinkedHashMap<>();
    j.put("format", 1);
    j.put("generator", "dexter-seed");
    j.put("seed", cfg.seed());
    j.put("size", size);
    j.put("dataVersion", RefData.VERSION);
    j.put("dataSha256", ref.dataSha256);
    j.put("windowFrom", WINDOW_START);
    j.put("windowDays", WINDOW_DAYS);
    j.put("zone", ZONE.getId());
    j.put("tables", tables);
    j.put("fixtures", fixtureSha);
    j.put("checksum", checksum);
    Files.writeString(cfg.out().resolve("manifest.json"), Json.write(j));
  }

  static String sha256Hex(String s) {
    return sha256Hex(s.getBytes(StandardCharsets.UTF_8));
  }

  static String sha256Hex(byte[] b) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String pgTime(Instant t) {
    return t.atOffset(ZoneOffset.UTC).toLocalDateTime().toString().replace('T', ' ') + "+00";
  }

  private static double clamp(double v, double lo, double hi) {
    return Math.max(lo, Math.min(hi, v));
  }

  // ======================================================================== word lists

  static final List<String> CITIES = List.of("BANGALORE", "MUMBAI", "NEW DELHI", "CHENNAI", "HYDERABAD", "PUNE",
      "KOLKATA", "GURGAON", "NOIDA", "AHMEDABAD", "JAIPUR", "KOCHI", "COIMBATORE", "LUCKNOW", "INDORE", "MYSURU");
  static final List<String> UPI_HANDLES = List.of("okaxis", "ybl", "oksbi", "okhdfcbank", "okicici", "paytm", "ibl",
      "axl");
  static final List<String> LOCAL_PAYEES = List.of("Sri Lakshmi Kirana", "Ganesh Tea Stall", "Ramesh Vegetables",
      "Anand Milk Supply", "Sharma Dairy", "Shree Krishna Sweets", "Raju Auto", "Kumar Tailors", "Mahalakshmi Flowers",
      "Iyer Mess", "Patel Provision Store", "Venkat Laundry", "Murugan Fruits", "Local Vendor", "Priya S", "Arjun M",
      "Neha K", "Rahul V", "Divya R", "Karthik P", "Self transfer");
  static final Map<String, List<String>> NOTES = Map.of(
      "food", List.of("team lunch", "weekend dinner", "groceries for the week", "office snacks", "birthday treat"),
      "travel", List.of("trip home", "airport cab", "weekend getaway", "office commute"),
      "shopping", List.of("gift for amma", "new phone case", "work shoes", "home essentials"),
      "utilities", List.of("monthly bill", "paid late", "autopay failed, paid manually"),
      "health", List.of("medicines for appa", "annual check-up", "gym renewal"));
  static final List<String> NOTES_DEFAULT = List.of("reimbursable", "shared with flatmates", "misc", "split later");
  static final List<String> NOTES_FESTIVE = List.of("Diwali shopping", "Diwali gifts", "festival sweets",
      "Navratri outfit", "Dhanteras");
  static final List<List<String>> TAG_SETS = List.of(List.of("family"), List.of("work"), List.of("reimbursable"),
      List.of("trip"), List.of("shared"), List.of("family", "festival"));
}
