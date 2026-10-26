package in.dexter.portfolio;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.contracts.events.aiparser.StatementParsedV1;
import in.dexter.events.EventEnvelope;
import in.dexter.portfolio.app.StatementService;
import in.dexter.portfolio.app.StatementService.Outcome;
import in.dexter.portfolio.support.Events;
import in.dexter.portfolio.support.PortfolioIT;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * MASTER_PLAN P12 statement uploads, end to end inside portfolio (the parser's event is handed to the consumer's
 * handler; Kafka + the real Python parser run in api-smoke): create (presigned PUT) → submit (scan, clean copy,
 * event, Mode B) → parsed (staged lines, duplicates flagged) → review → confirm (card transactions, idempotent per
 * line) / discard; refused files, wrong card, parser failures, recovery after a crash, an unreachable store, and
 * tenant isolation.
 */
class StatementsIT extends PortfolioIT {
  static final String PATH = "/v1/statements/uploads";
  static final ZoneId IST = ZoneId.of("Asia/Kolkata");

  @Autowired
  StatementService statements;

  @AfterEach
  void storeUp() {
    FILES.down.set(false);
  }

  static byte[] pdf(String marker) {
    return ("%PDF-1.4\n% test statement " + marker + "\n%%EOF\n").getBytes(StandardCharsets.US_ASCII);
  }

  String last4(UUID account, UUID card) {
    return get("/v1/cards/" + card, account).json().get("last4").asString();
  }

  JsonNode create(UUID account, UUID card) {
    Map<String, Object> b = new LinkedHashMap<>();
    b.put("userCardId", card);
    b.put("fileName", "statement.pdf");
    Res r = post(PATH, account, b);
    assertThat(r.status()).as(r.toString()).isEqualTo(201);
    return r.json();
  }

  Res submit(UUID account, String uploadId, UUID key) {
    return send("POST", PATH + "/" + uploadId + ":submit", null, null,
        auth(account, "Idempotency-Key", key.toString()));
  }

  /** create → upload the bytes → submit; returns the upload id. */
  String submitted(UUID account, UUID card, byte[] file) {
    JsonNode u = create(account, card);
    String id = u.get("id").asString();
    FILES.upload(quarantineKey(account, id), file);
    Res s = submit(account, id, UUID.randomUUID());
    assertThat(s.status()).as(s.toString()).isEqualTo(202);
    return id;
  }

  static String quarantineKey(UUID account, String id) {
    return "quarantine/" + account + "/" + id + ".pdf";
  }

  static String day(int daysAgo) {
    return LocalDate.now(IST).minusDays(daysAgo).toString();
  }

  static List<StatementParsedV1.Line> fourLines(String tag) {
    return List.of(Events.line(1, day(9), "SWIGGY*BANGALORE " + tag, 45_000, "purchase"),
        Events.line(2, day(8), "REFUND SWIGGY " + tag, 5_000, "refund"),
        Events.line(3, day(7), "ANNUAL FEE " + tag, 50_000, "fee"),
        Events.line(4, day(6), "PAYMENT RECEIVED XXXX XXXX XXXX 1111 " + tag, 100_000, "payment"));
  }

  Res confirm(UUID account, String id, List<Integer> exclude) {
    String body = exclude == null ? null : JSON.writeValueAsString(Map.of("excludeLines", exclude));
    return send("POST", PATH + "/" + id + ":confirm", body, "application/json",
        auth(account, "Idempotency-Key", UUID.randomUUID().toString()));
  }

  static String status(String id) {
    return ADMIN.sql("SELECT status FROM portfolio.statement_uploads WHERE id = CAST(:id AS uuid)").param("id", id)
        .query(String.class).single();
  }

  @Test
  void uploadSubmitParseReviewConfirm() {
    UUID asha = UUID.randomUUID();
    UUID card = newCard(asha, POINTS_CARD);
    String last4 = last4(asha, card);

    // create: 201, presigned PUT to a quarantine key, replay with the same key
    Map<String, Object> body = Map.of("userCardId", card, "fileName", "sep.pdf", "sizeBytes", 1234);
    UUID key = UUID.randomUUID();
    Res c = send("POST", PATH, JSON.writeValueAsString(body), "application/json",
        auth(asha, "Idempotency-Key", key.toString()));
    assertThat(c.status()).isEqualTo(201);
    JsonNode u = c.json();
    String id = u.get("id").asString();
    assertThat(c.header("Location")).isEqualTo(PATH + "/" + id);
    assertThat(u.get("status").asString()).isEqualTo("awaiting_upload");
    assertThat(u.get("upload").get("method").asString()).isEqualTo("PUT");
    assertThat(u.get("upload").get("url").asString()).contains(quarantineKey(asha, id));
    assertThat(u.get("cardLast4").asString()).isEqualTo(last4);
    Res again = send("POST", PATH, JSON.writeValueAsString(body), "application/json",
        auth(asha, "Idempotency-Key", key.toString()));
    assertThat(again.header("Idempotent-Replayed")).isEqualTo("true");
    assertThat(again.json().get("id").asString()).isEqualTo(id);

    // submit before the file is there: 409, and the key is released (a retry with it works later)
    UUID submitKey = UUID.randomUUID();
    Res early = submit(asha, id, submitKey);
    assertThat(early.status()).isEqualTo(409);
    assertThat(early.code()).isEqualTo("STATEMENT_NOT_UPLOADED");

    byte[] file = pdf("sep-" + id);
    FILES.upload(quarantineKey(asha, id), file);
    Res s = submit(asha, id, submitKey);
    assertThat(s.status()).as(s.toString()).isEqualTo(202);
    assertThat(s.json().get("status").asString()).isEqualTo("submitted");
    String sha = HexFormat.of().formatHex(in.dexter.portfolio.domain.StatementScan.sha256(file));
    assertThat(s.json().get("sha256").asString()).isEqualTo(sha);
    assertThat(FILES.objects).doesNotContainKey(quarantineKey(asha, id))
        .containsKey("statements/" + asha + "/" + id + ".pdf");
    assertThat(outboxRows(asha, "portfolio.statement.submitted")).isEqualTo(1);
    JsonNode event = JSON.readTree(ADMIN.sql("SELECT payload::text FROM portfolio.outbox WHERE message_key = :k"
        + " AND event_type = 'portfolio.statement.submitted'").param("k", asha.toString()).query(String.class).single());
    assertThat(event.get("data").get("fileSha256").asString()).isEqualTo(sha);
    assertThat(event.get("data").get("last4").asString()).isEqualTo(last4);
    assertThat(event.get("data").get("fileUrl").asString()).contains("statements/" + asha + "/" + id + ".pdf");
    assertThat(event.get("data").get("sizeBytes").asLong()).isEqualTo(file.length);

    Res replay = submit(asha, id, submitKey);
    assertThat(replay.status()).isEqualTo(202);
    assertThat(replay.header("Idempotent-Replayed")).isEqualTo("true");
    Res other = submit(asha, id, UUID.randomUUID());
    assertThat(other.status()).isEqualTo(409);
    assertThat(other.code()).isEqualTo("STATEMENT_WRONG_STATE");
    assertThat(outboxRows(asha, "portfolio.statement.submitted")).isEqualTo(1);

    // the parser's result: staged once (a redelivered event is dropped by the inbox)
    EventEnvelope parsed = Events.parsed(asha, UUID.fromString(id), card, last4, fourLines(id));
    assertThat(statements.onParsed(parsed)).isEqualTo(Outcome.APPLIED);
    assertThat(statements.onParsed(parsed)).isEqualTo(Outcome.DUPLICATE);
    JsonNode detail = get(PATH + "/" + id, asha).json();
    assertThat(detail.get("status").asString()).isEqualTo("parsed");
    JsonNode p = detail.get("parsed");
    assertThat(p.get("lineCount").asInt()).isEqualTo(4);
    assertThat(p.get("cardMatches").booleanValue()).isTrue();
    assertThat(p.get("debitsPaise").asLong()).isEqualTo(95_000);
    assertThat(p.get("creditsPaise").asLong()).isEqualTo(105_000);
    assertThat(p.get("issuer").asString()).isEqualTo("MOCK_SAFFRON");

    // review: lines in statement order, keyset pages
    JsonNode first = get(PATH + "/" + id + "/lines?limit=3", asha).json();
    assertThat(first.get("data")).hasSize(3);
    assertThat(first.get("data").get(0).get("lineNo").asInt()).isEqualTo(1);
    assertThat(first.get("data").get(0).get("status").asString()).isEqualTo("pending");
    assertThat(first.get("page").get("hasMore").booleanValue()).isTrue();
    String cursor = first.get("page").get("nextCursor").asString();
    JsonNode second = get(PATH + "/" + id + "/lines?limit=3&cursor="
        + java.net.URLEncoder.encode(cursor, StandardCharsets.UTF_8), asha).json();
    assertThat(second.get("data")).hasSize(1);
    assertThat(second.get("data").get(0).get("lineNo").asInt()).isEqualTo(4);

    // confirm without line 3: three card transactions (source statement), the files are gone
    long recordedBefore = outboxRows(asha, "portfolio.transaction.recorded");
    Res done = confirm(asha, id, List.of(3));
    assertThat(done.status()).as(done.toString()).isEqualTo(200);
    JsonNode result = done.json().get("result");
    assertThat(result.get("imported").asInt()).isEqualTo(3);
    assertThat(result.get("excluded").asInt()).isEqualTo(1);
    assertThat(result.get("duplicates").asInt()).isZero();
    assertThat(done.json().get("status").asString()).isEqualTo("confirmed");
    assertThat(outboxRows(asha, "portfolio.transaction.recorded")).isEqualTo(recordedBefore + 3);
    JsonNode txns = get("/v1/cards/" + card + "/transactions?limit=10", asha).json().get("data");
    List<String> sources = new ArrayList<>();
    txns.forEach(t -> sources.add(t.get("source").asString()));
    assertThat(sources).containsExactly("statement", "statement", "statement");
    List<String> kinds = new ArrayList<>();
    txns.forEach(t -> kinds.add(t.get("kind").asString()));
    assertThat(kinds).containsExactlyInAnyOrder("purchase", "refund", "payment");
    assertThat(FILES.objects).doesNotContainKey("statements/" + asha + "/" + id + ".pdf");
    JsonNode lines = get(PATH + "/" + id + "/lines?limit=10", asha).json().get("data");
    assertThat(lines.get(0).get("status").asString()).isEqualTo("imported");
    assertThat(lines.get(0).get("cardTxnId").isNull()).isFalse();
    assertThat(lines.get(2).get("status").asString()).isEqualTo("excluded");
    // the purchase earned points on the card's terms (the same write path as a manual record)
    long points = ADMIN.sql("SELECT coalesce(sum(points), 0) FROM portfolio.reward_points_ledger WHERE user_card_id"
        + " = :c").param("c", card).query(Long.class).single();
    assertThat(points).isPositive();

    Res twice = confirm(asha, id, null);
    assertThat(twice.status()).isEqualTo(409);
    assertThat(twice.code()).isEqualTo("STATEMENT_WRONG_STATE");

    // a second, different file with the same lines plus one: only the new ones import
    String id2 = submitted(asha, card, pdf("sep-again-" + id));
    List<StatementParsedV1.Line> more = new ArrayList<>(fourLines(id));
    more.add(Events.line(5, day(5), "UBER INDIA " + id, 12_300, "purchase"));
    assertThat(statements.onParsed(Events.parsed(asha, UUID.fromString(id2), card, last4, more)))
        .isEqualTo(Outcome.APPLIED);
    JsonNode d2 = get(PATH + "/" + id2, asha).json();
    assertThat(d2.get("parsed").get("duplicateLines").asInt()).isEqualTo(3);
    JsonNode r2 = confirm(asha, id2, null).json().get("result");
    assertThat(r2.get("imported").asInt()).as("the previously excluded fee + the new purchase").isEqualTo(2);
    assertThat(r2.get("duplicates").asInt()).isEqualTo(3);
    assertThat(get("/v1/cards/" + card + "/transactions?limit=10", asha).json().get("data")).hasSize(5);

    // the very same file again for the card: refused while the first one is live (it is confirmed)
    JsonNode u3 = create(asha, card);
    String id3 = u3.get("id").asString();
    FILES.upload(quarantineKey(asha, id3), file);
    Res dup = submit(asha, id3, UUID.randomUUID());
    assertThat(dup.status()).isEqualTo(409);
    assertThat(dup.code()).isEqualTo("STATEMENT_ALREADY_UPLOADED");
    assertThat(dup.json().get("uploadId").asString()).isEqualTo(id);
    assertThat(status(id3)).isEqualTo("awaiting_upload");
  }

  @Test
  void rejectedFilesFailTheUpload() {
    UUID a = UUID.randomUUID();
    UUID card = newCard(a, CASHBACK_CARD);
    for (Object[] t : new Object[][] {{"PK\u0003\u0004 a zip".getBytes(StandardCharsets.ISO_8859_1), "NOT_A_PDF"},
        {new byte[0], "EMPTY_FILE"}, {new byte[10 * 1024 * 1024 + 1], "FILE_TOO_LARGE"}}) {
      byte[] bytes = (byte[]) t[0];
      if (bytes.length > 100) {
        System.arraycopy("%PDF-".getBytes(StandardCharsets.US_ASCII), 0, bytes, 0, 5);
      }
      String id = create(a, card).get("id").asString();
      FILES.upload(quarantineKey(a, id), bytes);
      Res r = submit(a, id, UUID.randomUUID());
      assertThat(r.status()).as(r.toString()).isEqualTo(422);
      assertThat(r.code()).isEqualTo("STATEMENT_FILE_REJECTED");
      assertThat(r.json().get("reason").asString()).isEqualTo(t[1]);
      assertThat(status(id)).isEqualTo("failed");
      assertThat(FILES.objects).doesNotContainKey(quarantineKey(a, id));
      assertThat(submit(a, id, UUID.randomUUID()).code()).isEqualTo("STATEMENT_WRONG_STATE");
    }
    assertThat(outboxRows(a, "portfolio.statement.submitted")).isZero();
  }

  @Test
  void parserFailuresWrongCardAndDiscard() {
    UUID a = UUID.randomUUID();
    UUID card = newCard(a, POINTS_CARD);
    String last4 = last4(a, card);

    String failed = submitted(a, card, pdf("failed-" + UUID.randomUUID()));
    assertThat(statements.onParsed(Events.parseFailed(a, UUID.fromString(failed), card, "UNKNOWN_FORMAT")))
        .isEqualTo(Outcome.FAILED);
    JsonNode f = get(PATH + "/" + failed, a).json();
    assertThat(f.get("status").asString()).isEqualTo("failed");
    assertThat(f.get("errorCode").asString()).isEqualTo("UNKNOWN_FORMAT");
    assertThat(f.get("parsedAt").isNull()).isFalse();
    assertThat(statements.onParsed(Events.parsed(a, UUID.fromString(failed), card, last4, fourLines("late"))))
        .as("a result for an upload that already failed").isEqualTo(Outcome.STALE);

    String future = submitted(a, card, pdf("future-" + UUID.randomUUID()));
    List<StatementParsedV1.Line> lines = List.of(Events.line(1, day(-3), "TOMORROW'S PURCHASE", 1_000, "purchase"));
    assertThat(statements.onParsed(Events.parsed(a, UUID.fromString(future), card, last4, lines)))
        .isEqualTo(Outcome.FAILED);
    assertThat(get(PATH + "/" + future, a).json().get("errorCode").asString()).isEqualTo("FUTURE_DATED_LINES");

    String other = last4.equals("9999") ? "1111" : "9999";
    byte[] wrongFile = pdf("wrong-" + UUID.randomUUID());
    String wrong = submitted(a, card, wrongFile);
    assertThat(statements.onParsed(Events.parsed(a, UUID.fromString(wrong), card, other, fourLines(wrong))))
        .isEqualTo(Outcome.APPLIED);
    assertThat(get(PATH + "/" + wrong, a).json().get("parsed").get("cardMatches").booleanValue()).isFalse();
    Res mismatch = confirm(a, wrong, null);
    assertThat(mismatch.status()).isEqualTo(409);
    assertThat(mismatch.code()).isEqualTo("STATEMENT_CARD_MISMATCH");

    Res discarded = send("POST", PATH + "/" + wrong + ":discard", null, null, auth(a));
    assertThat(discarded.status()).as(discarded.toString()).isEqualTo(200);
    assertThat(discarded.json().get("status").asString()).isEqualTo("discarded");
    assertThat(FILES.objects).doesNotContainKey("statements/" + a + "/" + wrong + ".pdf");
    assertThat(send("POST", PATH + "/" + wrong + ":discard", null, null, auth(a)).status()).isEqualTo(200);
    assertThat(confirm(a, wrong, null).code()).isEqualTo("STATEMENT_WRONG_STATE");
    assertThat(send("POST", PATH + "/" + failed + ":discard", null, null, auth(a)).code())
        .isEqualTo("STATEMENT_WRONG_STATE");

    // after a discard the same file may be uploaded again
    String again = create(a, card).get("id").asString();
    FILES.upload(quarantineKey(a, again), wrongFile);
    assertThat(submit(a, again, UUID.randomUUID()).status()).isEqualTo(202);
  }

  /** Mode B (ADR-0017): a crash after the clean copy resumes from the recovery point without scanning again. */
  @Test
  void submitResumesFromTheRecoveryPoint() throws Exception {
    UUID a = UUID.randomUUID();
    UUID card = newCard(a, POINTS_CARD);
    String id = create(a, card).get("id").asString();
    FILES.upload(quarantineKey(a, id), pdf("crash-" + id));
    UUID key = UUID.randomUUID();
    int readsBefore = FILES.reads.get();
    statements.failAfterScan();
    Res crashed = submit(a, id, key);
    assertThat(crashed.status()).isEqualTo(500);
    assertThat(FILES.reads.get()).isEqualTo(readsBefore + 1);
    assertThat(FILES.objects).containsKey("statements/" + a + "/" + id + ".pdf");
    assertThat(status(id)).isEqualTo("awaiting_upload");
    assertThat(ADMIN.sql("SELECT recovery_point FROM portfolio.idempotency_keys WHERE idem_key = :k").param("k", key)
        .query(String.class).single()).isEqualTo("scanned");

    Res busy = submit(a, id, key);
    assertThat(busy.status()).as("the lease (1 s in the ITs) is still held").isEqualTo(409);
    assertThat(busy.code()).isEqualTo("IDEMPOTENCY_IN_PROGRESS");

    Thread.sleep(1_300);
    Res resumed = submit(a, id, key);
    assertThat(resumed.status()).as(resumed.toString()).isEqualTo(202);
    assertThat(FILES.reads.get()).as("no second scan").isEqualTo(readsBefore + 1);
    assertThat(outboxRows(a, "portfolio.statement.submitted")).isEqualTo(1);
    assertThat(submit(a, id, key).header("Idempotent-Replayed")).isEqualTo("true");
  }

  @Test
  void anUnreachableStoreIs503AndTheSameKeyWorksLater() {
    UUID a = UUID.randomUUID();
    UUID card = newCard(a, POINTS_CARD);
    String id = create(a, card).get("id").asString();
    FILES.upload(quarantineKey(a, id), pdf("down-" + id));
    UUID key = UUID.randomUUID();
    FILES.down.set(true);
    Res down = submit(a, id, key);
    assertThat(down.status()).isEqualTo(503);
    assertThat(down.code()).isEqualTo("OBJECT_STORE_UNAVAILABLE");
    assertThat(down.header("Retry-After")).isEqualTo("5");
    FILES.down.set(false);
    assertThat(submit(a, id, key).status()).isEqualTo(202);
  }

  @Test
  void tenantsAreIsolatedAndInputIsChecked() {
    UUID asha = UUID.randomUUID();
    UUID ravi = UUID.randomUUID();
    UUID card = newCard(asha, POINTS_CARD);
    String id = submitted(asha, card, pdf("iso-" + UUID.randomUUID()));

    assertThat(get(PATH + "/" + id, ravi).status()).isEqualTo(404);
    assertThat(get(PATH + "/" + id + "/lines", ravi).status()).isEqualTo(404);
    assertThat(submit(ravi, id, UUID.randomUUID()).status()).isEqualTo(404);
    assertThat(confirm(ravi, id, null).status()).isEqualTo(404);
    assertThat(get(PATH, ravi).json().get("data")).isEmpty();
    assertThat(get(PATH + "?cardId=" + card, asha).json().get("data")).hasSize(1);
    assertThat(statements.onParsed(Events.parsed(ravi, UUID.fromString(id), card, "0000", fourLines("x"))))
        .as("another account's event cannot reach the upload (RLS)").isEqualTo(Outcome.IGNORED);
    assertThat(status(id)).isEqualTo("submitted");

    Map<String, Object> rogue = Map.of("userCardId", card, "fileName", "x.pdf");
    Res r = post(PATH, ravi, rogue);
    assertThat(r.status()).isEqualTo(400);
    assertThat(r.json().get("errors").get(0).get("code").asString()).isEqualTo("UNKNOWN_CARD");
    assertThat(post(PATH, asha, Map.of("fileName", "x.pdf")).status()).isEqualTo(400);
    assertThat(post(PATH, asha, Map.of("userCardId", card, "fileName", "x.pdf", "sizeBytes", 20_000_000)).status())
        .isEqualTo(400);
    assertThat(send("POST", PATH, "{\"userCardId\":\"" + card + "\",\"fileName\":\"x.pdf\"}", "application/json",
        auth(asha)).status()).as("no Idempotency-Key").isEqualTo(400);
    assertThat(send("POST", PATH + "/" + id + ":submit", null, null, auth(asha)).code())
        .isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
    assertThat(send("POST", PATH + "/" + id + ":explode", null, null, auth(asha)).status()).isEqualTo(404);
    assertThat(send("GET", PATH, null, null).status()).isEqualTo(401);
  }
}
