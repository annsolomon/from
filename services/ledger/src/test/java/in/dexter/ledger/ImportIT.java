package in.dexter.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.ledger.support.LabelledMerchants;
import in.dexter.ledger.support.LedgerIT;
import in.dexter.ledger.support.SeedMerchants;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * CSV import (MASTER_PLAN P08): presigned upload → start → async job (COPY staging, validate, resolve, merge). A
 * 10,000-row file imports once; uploading the same file again creates nothing (every row's natural key exists).
 * Row errors are reported with their row numbers; malformed and missing files fail the job.
 */
class ImportIT extends LedgerIT {
  static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ImportIT.class);
  static final String HEADER = "date,amount,merchant,category,payment_method,note,client_ref\n";

  /** After the context (Flyway) is up: a static @BeforeAll can run before the first test class migrates. */
  @org.junit.jupiter.api.BeforeEach
  void merchants() {
    SeedMerchants.ensureLoaded(ADMIN);
  }

  /** POST /v1/imports, upload (in-memory store), :start; returns the job id. */
  UUID upload(UUID account, byte[] csv, boolean put) {
    Res c = post("/v1/imports", account, Map.of("fileName", "statement.csv", "sizeBytes", csv.length));
    assertThat(c.status()).as(c.toString()).isEqualTo(201);
    JsonNode job = c.json();
    assertThat(job.get("status").asString()).isEqualTo("awaiting_upload");
    assertThat(job.get("upload").get("method").asString()).isEqualTo("PUT");
    assertThat(job.get("csvHeader").asString()).isEqualTo(HEADER.strip());
    if (put) {
      FILES.put(job.get("upload").get("url").asString().substring("memory://".length()), csv);
    }
    UUID id = UUID.fromString(job.get("id").asString());
    Res s = post("/v1/imports/" + id + ":start", account, null);
    assertThat(s.status()).as(s.toString()).isEqualTo(202);
    return id;
  }

  JsonNode await(UUID account, UUID id) {
    long until = System.nanoTime() + Duration.ofSeconds(90).toNanos();
    while (System.nanoTime() < until) {
      JsonNode j = get("/v1/imports/" + id, account).json();
      String st = j.get("status").asString();
      if (st.equals("completed") || st.equals("failed")) {
        return j;
      }
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
    }
    throw new AssertionError("import " + id + " did not finish");
  }

  long expenses(UUID a) {
    return ADMIN.sql("SELECT count(1) FROM ledger.expenses WHERE account_id = :a").param("a", a)
        .query(Long.class).single();
  }

  static byte[] tenThousandRows() {
    List<LabelledMerchants.Merchant> ms = LabelledMerchants.merchants();
    Random x = new Random(11);
    StringBuilder sb = new StringBuilder(HEADER);
    for (int i = 1; i <= 10_000; i++) {
      LocalDate d = LocalDate.of(2026, 1, 1).plusDays(x.nextInt(270));
      LabelledMerchants.Merchant m = ms.get(x.nextInt(ms.size()));
      String line = switch (i) {
        case 17 -> "2026-13-01,10.00,Bad date,,upi,,";
        case 42 -> "2026-02-01,-5,Refund,,upi,,";
        case 99 -> "2026-02-01,10.00,Mystery,made.up,upi,,";
        case 5001 -> "2026-03-03,250.00,\"Chai, Point\",,cash,\"same, twice\","; // identical to 5000 on purpose
        case 5000 -> "2026-03-03,250.00,\"Chai, Point\",,cash,\"same, twice\",";
        default -> (i % 2 == 0 ? d.toString() : d.getDayOfMonth() + "/" + d.getMonthValue() + "/" + d.getYear()) + ","
            + java.math.BigDecimal.valueOf(100 + x.nextInt(500_000), 2).toPlainString() + "," + m.name().toUpperCase(java.util.Locale.ROOT) + " BANGALORE,"
            + (i % 5 == 0 ? m.category() : "") + "," + (i % 3 == 0 ? "card" : "upi") + ",,";
      };
      sb.append(line).append('\n');
    }
    return sb.toString().getBytes(StandardCharsets.UTF_8);
  }

  @Test
  void tenThousandRowsImportOnceAndReimportCreatesNothing() {
    UUID a = UUID.randomUUID();
    byte[] csv = tenThousandRows();
    long t0 = System.nanoTime();
    JsonNode j = await(a, upload(a, csv, true));
    LOG.info("IMPORT_10K ms={}", (System.nanoTime() - t0) / 1_000_000);
    assertThat(j.get("status").asString()).as(j.toString()).isEqualTo("completed");
    assertThat(j.get("total").asInt()).isEqualTo(10_000);
    assertThat(j.get("failed").asInt()).isEqualTo(3);
    assertThat(j.get("ok").asInt()).isEqualTo(9_997);
    assertThat(j.get("duplicates").asInt()).isZero();
    JsonNode errors = j.get("errors");
    assertThat(errors.size()).isEqualTo(3);
    assertThat(errors.get(0).get("rowNo").asInt()).isEqualTo(17);
    assertThat(errors.get(0).get("code").asString()).isEqualTo("DATE_INVALID");
    assertThat(errors.get(1).get("code").asString()).isEqualTo("AMOUNT_INVALID");
    assertThat(errors.get(2).get("code").asString()).isEqualTo("CATEGORY_UNKNOWN");
    assertThat(expenses(a)).as("both identical rows count").isEqualTo(9_997);
    assertThat(outboxRows(a, "ledger.expense.created")).isEqualTo(9_997);
    assertThat(outboxRows(a, "ledger.import.completed")).isEqualTo(1);
    assertThat(ADMIN.sql("SELECT count(1) FROM ledger.expenses WHERE account_id = :a AND merchant_id IS NOT NULL")
        .param("a", a).query(Long.class).single()).as("merchants resolved").isGreaterThan(9_000);
    long daily = ADMIN.sql("SELECT sum(total_paise) FROM ledger.expense_daily_totals WHERE account_id = :a")
        .param("a", a).query(Long.class).single();
    long rows = ADMIN.sql("SELECT sum(amount_paise) FROM ledger.expenses WHERE account_id = :a").param("a", a)
        .query(Long.class).single();
    assertThat(daily).isEqualTo(rows);

    JsonNode again = await(a, upload(a, csv, true));
    assertThat(again.get("status").asString()).isEqualTo("completed");
    assertThat(again.get("ok").asInt()).isEqualTo(9_997);
    assertThat(again.get("duplicates").asInt()).isEqualTo(9_997);
    assertThat(expenses(a)).as("re-import is idempotent").isEqualTo(9_997);
    assertThat(outboxRows(a, "ledger.expense.created")).isEqualTo(9_997);

    UUID done = UUID.fromString(again.get("id").asString());
    assertThat(post("/v1/imports/" + done + ":start", a, null).code()).isEqualTo("IMPORT_NOT_STARTABLE");
    assertThat(get("/v1/imports/" + done, UUID.randomUUID()).status()).as("another tenant").isEqualTo(404);
  }

  @Test
  void malformedAndMissingFilesFailTheJob() {
    UUID a = UUID.randomUUID();
    JsonNode bad = await(a, upload(a, "when,how much\n2026-01-01,5\n".getBytes(StandardCharsets.UTF_8), true));
    assertThat(bad.get("status").asString()).isEqualTo("failed");
    assertThat(bad.get("errorCode").asString()).isEqualTo("CSV_INVALID");
    assertThat(bad.get("errorDetail").asString()).contains("header");

    JsonNode shortRow = await(a, upload(a, (HEADER + "2026-01-01,5\n").getBytes(StandardCharsets.UTF_8), true));
    assertThat(shortRow.get("errorCode").asString()).isEqualTo("CSV_INVALID");
    assertThat(shortRow.get("errorDetail").asString()).contains("line 2");

    JsonNode missing = await(a, upload(a, HEADER.getBytes(StandardCharsets.UTF_8), false));
    assertThat(missing.get("errorCode").asString()).isEqualTo("FILE_MISSING");
    assertThat(expenses(a)).isZero();
    assertThat(outboxRows(a, "ledger.import.completed")).isEqualTo(3);
  }
}
