package in.dexter.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Determinism (MASTER_PLAN P03: same seed → same checksum) and the invariants later phases rely on, on a dataset
 * big enough to have bulk accounts but quick to build.
 */
class GeneratorTest {
  static final Map<String, String> PW = Map.of("DEMO_PASSWORD_ASHA", "Asha-Demo-2026!", "DEMO_PASSWORD_RAVI",
      "Ravi-Demo-2026!", "DEMO_PASSWORD_ADMIN", "Admin-Demo-2026!", "SEED_BULK_PASSWORD", "Seed-Bulk-2026!");
  static final Size TEST = Size.parse("custom:40,6000,120");

  @TempDir
  static Path tmp;
  static Generator.Result first;
  static Path dir;

  @BeforeAll
  static void generate() {
    dir = tmp.resolve("a");
    first = new Generator(new Generator.Config(42, TEST, dir, tmp.resolve("a-fx"), PW)).run();
  }

  static List<String[]> rows(Path d, Table t) throws IOException {
    List<String[]> out = new ArrayList<>();
    for (String line : Files.readAllLines(d.resolve(t.file()))) {
      out.add(line.split("\t", -1));
    }
    return out;
  }

  static int col(Table t, String name) {
    return t.columnNames().indexOf(name);
  }

  // ------------------------------------------------------------------ determinism

  @Test
  void sameSeedGivesTheSameBytesAndChecksum() throws IOException {
    Path again = tmp.resolve("b");
    Generator.Result second = new Generator(new Generator.Config(42, TEST, again, tmp.resolve("b-fx"), PW)).run();
    assertThat(second.checksum()).isEqualTo(first.checksum());
    for (Table t : Tables.ALL) {
      assertThat(Files.mismatch(dir.resolve(t.file()), again.resolve(t.file()))).as(t.file()).isEqualTo(-1L);
    }
    assertThat(Files.readString(again.resolve("manifest.json"))).isEqualTo(Files.readString(dir.resolve("manifest.json")));
  }

  @Test
  void anotherSeedChangesUserDataButNotReferenceIds() throws IOException {
    Path other = tmp.resolve("c");
    Generator.Result r = new Generator(new Generator.Config(43, TEST, other, null, PW)).run();
    assertThat(r.checksum()).isNotEqualTo(first.checksum());
    assertThat(Files.mismatch(dir.resolve(Tables.EXPENSES.file()), other.resolve(Tables.EXPENSES.file())))
        .isNotEqualTo(-1L);
    for (Table t : List.of(Tables.MERCHANTS, Tables.CATEGORIES, Tables.CARD_PRODUCTS, Tables.REWARD_RULES,
        Tables.ISSUERS)) {
      assertThat(Files.mismatch(dir.resolve(t.file()), other.resolve(t.file()))).as(t.file()).isEqualTo(-1L);
    }
  }

  @Test
  void demoAccountsAreTheSameInEverySize() throws IOException {
    Path small = tmp.resolve("small-fx");
    new Generator(new Generator.Config(42, Size.SMALL, tmp.resolve("small"), small, PW)).run();
    try (Stream<Path> files = Files.list(small)) {
      for (Path f : files.toList()) {
        assertThat(Files.mismatch(f, tmp.resolve("a-fx").resolve(f.getFileName()))).as(f.toString()).isEqualTo(-1L);
      }
    }
  }

  @Test
  void passwordsAreRequired() {
    Map<String, String> missing = new HashMap<>(PW);
    missing.remove("DEMO_PASSWORD_RAVI");
    assertThatThrownBy(() -> new Generator(new Generator.Config(42, Size.SMALL, tmp.resolve("x"), null, missing)).run())
        .hasMessageContaining("DEMO_PASSWORD_RAVI");
  }

  // ------------------------------------------------------------------ invariants

  @Test
  void totalsAreExact() throws IOException {
    assertThat(rows(dir, Tables.ACCOUNTS)).hasSize(TEST.accounts());
    assertThat(rows(dir, Tables.EXPENSES)).hasSize((int) TEST.expenses());
    assertThat(rows(dir, Tables.USER_CARDS)).hasSize(TEST.userCards());
    assertThat(rows(dir, Tables.ADMINS)).hasSize(1);
    assertThat(rows(dir, Tables.CARD_PRODUCTS)).hasSize(40)
        .allSatisfy(r -> assertThat(r[col(Tables.CARD_PRODUCTS, "name")]).startsWith("MOCK_"));
    assertThat(rows(dir, Tables.MERCHANTS).size()).isGreaterThanOrEqualTo(300);
    assertThat(first.rows()).containsEntry("ledger.expenses", TEST.expenses());
  }

  @Test
  void eachDemoAccountHasFiveHundredExpensesAndItsCards() throws IOException {
    Map<String, String> idByEmail = new HashMap<>();
    for (String[] r : rows(dir, Tables.ACCOUNTS)) {
      idByEmail.put(r[col(Tables.ACCOUNTS, "email")], r[0]);
    }
    Map<String, Integer> perAccount = new HashMap<>();
    for (String[] r : rows(dir, Tables.EXPENSES)) {
      perAccount.merge(r[col(Tables.EXPENSES, "account_id")], 1, Integer::sum);
    }
    assertThat(perAccount.get(idByEmail.get("asha@demo.in"))).isEqualTo(500);
    assertThat(perAccount.get(idByEmail.get("ravi@demo.in"))).isEqualTo(500);
    Map<String, Integer> cards = new HashMap<>();
    for (String[] r : rows(dir, Tables.USER_CARDS)) {
      cards.merge(r[col(Tables.USER_CARDS, "account_id")], 1, Integer::sum);
    }
    assertThat(cards.get(idByEmail.get("asha@demo.in"))).isEqualTo(3);
    assertThat(cards.get(idByEmail.get("ravi@demo.in"))).isEqualTo(2);
    assertThat(cards.values()).allSatisfy(n -> assertThat(n).isBetween(1, Size.MAX_CARDS_PER_ACCOUNT));
  }

  @Test
  void expensesReferenceOnlyKnownRowsAndHonourThePlannedChecks() throws IOException {
    Set<String> accounts = new HashSet<>();
    rows(dir, Tables.ACCOUNTS).forEach(r -> accounts.add(r[0]));
    Map<String, String> cardOwner = new HashMap<>();
    rows(dir, Tables.USER_CARDS).forEach(r -> cardOwner.put(r[0], r[col(Tables.USER_CARDS, "account_id")]));
    Set<String> merchants = new HashSet<>();
    rows(dir, Tables.MERCHANTS).forEach(r -> merchants.add(r[0]));
    Set<String> categories = new HashSet<>();
    rows(dir, Tables.CATEGORIES).forEach(r -> categories.add(r[col(Tables.CATEGORIES, "path")]));
    Set<String> ids = new HashSet<>();
    Set<String> clientRefs = new HashSet<>();
    Set<String> methods = Set.of("card", "upi", "cash", "netbanking", "wallet", "other");
    Set<String> sources = Set.of("user", "resolver", "import", "rule");
    Instant from = Generator.WINDOW_START.atStartOfDay(Generator.ZONE).toInstant();
    Instant to = Generator.WINDOW_START.plusDays(Generator.WINDOW_DAYS).atStartOfDay(Generator.ZONE).toInstant();
    for (String[] r : rows(dir, Tables.EXPENSES)) {
      String account = r[col(Tables.EXPENSES, "account_id")];
      assertThat(ids.add(r[0])).as("unique id").isTrue();
      assertThat(UUID.fromString(r[0]).version()).isEqualTo(7);
      assertThat(accounts).contains(account);
      long amount = Long.parseLong(r[col(Tables.EXPENSES, "amount_paise")]);
      assertThat(amount).isPositive().isLessThanOrEqualTo(Generator.MAX_EXPENSE_PAISE);
      Instant occurred = Instant.parse(r[col(Tables.EXPENSES, "occurred_at")]);
      assertThat(occurred).isBetween(from, to);
      assertThat(Instant.parse(r[col(Tables.EXPENSES, "created_at")])).isAfterOrEqualTo(occurred);
      assertThat(categories).contains(r[col(Tables.EXPENSES, "category_path")]);
      assertThat(methods).contains(r[col(Tables.EXPENSES, "payment_method")]);
      assertThat(sources).contains(r[col(Tables.EXPENSES, "category_source")]);
      assertThat(Integer.parseInt(r[col(Tables.EXPENSES, "resolution_confidence")])).isBetween(0, 1000);
      String merchant = r[col(Tables.EXPENSES, "merchant_id")];
      if (!merchant.equals("\\N")) {
        assertThat(merchants).contains(merchant);
      }
      String card = r[col(Tables.EXPENSES, "user_card_id")];
      if (r[col(Tables.EXPENSES, "payment_method")].equals("card")) {
        assertThat(cardOwner.get(card)).as("card belongs to the account").isEqualTo(account);
      } else {
        assertThat(card).isEqualTo("\\N");
      }
      String ref = r[col(Tables.EXPENSES, "client_ref")];
      if (!ref.equals("\\N")) {
        assertThat(clientRefs.add(account + "|" + ref)).as("(account_id, client_ref) unique").isTrue();
      }
      String note = r[col(Tables.EXPENSES, "note")];
      assertThat(note.equals("\\N") || note.length() <= 500).isTrue();
    }
  }

  @Test
  void dailyTotalsEqualTheSumOfLiveExpenses() throws IOException {
    Map<String, long[]> expected = new HashMap<>();
    for (String[] r : rows(dir, Tables.EXPENSES)) {
      if (!r[col(Tables.EXPENSES, "deleted_at")].equals("\\N")) {
        continue;
      }
      Instant occurred = Instant.parse(r[col(Tables.EXPENSES, "occurred_at")]);
      LocalDate day = LocalDate.ofInstant(occurred, Generator.ZONE);
      String root = Generator.rootOf(r[col(Tables.EXPENSES, "category_path")]);
      long[] agg = expected.computeIfAbsent(r[col(Tables.EXPENSES, "account_id")] + "|" + day + "|" + root,
          k -> new long[2]);
      agg[0] += Long.parseLong(r[col(Tables.EXPENSES, "amount_paise")]);
      agg[1]++;
    }
    Map<String, long[]> actual = new HashMap<>();
    for (String[] r : rows(dir, Tables.EXPENSE_DAILY_TOTALS)) {
      String key = r[0] + "|" + r[1] + "|" + r[2];
      assertThat(actual.put(key, new long[] {Long.parseLong(r[3]), Long.parseLong(r[4])})).as("unique key").isNull();
    }
    assertThat(actual.keySet()).isEqualTo(expected.keySet());
    expected.forEach((k, v) -> assertThat(actual.get(k)).as(k).containsExactly(v));
  }

  @Test
  void festiveSeasonAndSalaryWeekShowInTheData() throws IOException {
    long festive = 0;
    long november = 0;
    for (String[] r : rows(dir, Tables.EXPENSES)) {
      LocalDate d = LocalDate.ofInstant(Instant.parse(r[col(Tables.EXPENSES, "occurred_at")]), Generator.ZONE);
      long amount = Long.parseLong(r[col(Tables.EXPENSES, "amount_paise")]);
      if (d.getMonthValue() == 10 && d.getYear() == 2025) {
        festive += amount;
      } else if (d.getMonthValue() == 11 && d.getYear() == 2025) {
        november += amount;
      }
    }
    assertThat(festive).isGreaterThan(november * 13 / 10); // Diwali month spends ≥ 30% more
  }

  @Test
  void manifestListsEveryTableWithRowsAndHashes() throws IOException {
    List<Loader.Entry> entries = Loader.readManifest(dir);
    assertThat(entries).extracting(Loader.Entry::table).containsExactlyElementsOf(
        Tables.ALL.stream().map(Table::name).toList());
    Loader.verifyFiles(dir, entries);
    assertThat(Files.readString(dir.resolve("manifest.json"))).contains("\"checksum\": \"" + first.checksum() + "\"");
    Files.writeString(dir.resolve(Tables.ISSUERS.file()), "tampered\n");
    assertThatThrownBy(() -> Loader.verifyFiles(dir, entries)).hasMessageContaining("does not match the manifest");
    // restore for the other tests (JUnit order is not fixed)
    new Generator(new Generator.Config(42, TEST, dir, tmp.resolve("a-fx"), PW)).run();
  }
}
