package in.dexter.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.contracts.events.demo.NoteCreatedV1;
import in.dexter.contracts.events.demo.NoteRenamedV1;
import in.dexter.contracts.events.gateway.ActivityRecordedV1;
import in.dexter.contracts.events.catalog.CardPublishedV1;
import in.dexter.contracts.events.catalog.OfferChangedV1;
import in.dexter.contracts.events.ledger.ExpenseChangedV1;
import in.dexter.contracts.events.ledger.ExpenseStateV1;
import in.dexter.contracts.events.ledger.ImportCompletedV1;
import in.dexter.contracts.events.planning.BudgetThresholdCrossedV1;
import in.dexter.contracts.events.planning.LiabilityChangedV1;
import in.dexter.contracts.events.portfolio.CardChangedV1;
import in.dexter.contracts.events.portfolio.LinkConfirmedV1;
import in.dexter.contracts.events.portfolio.StatementSubmittedV1;
import in.dexter.contracts.events.portfolio.TransactionRecordedV1;
import in.dexter.contracts.events.aiparser.StatementParsedV1;
import in.dexter.contracts.events.identity.AccountRegisteredV1;
import in.dexter.contracts.events.identity.AdminActionV1;
import in.dexter.contracts.events.identity.AccountVerifiedV1;
import in.dexter.contracts.events.identity.SecurityAlertV1;
import in.dexter.contracts.events.identity.SecurityAlertV2;
import in.dexter.contracts.events.identity.SessionRevokedV1;
import in.dexter.events.EnvelopeCodec;
import in.dexter.events.EventEnvelope;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Every event record matches its schema, and real envelopes match envelope.v1.json. */
class SchemaContractTest {
  static final JsonMapper MAPPER = JsonMapper.builder().build();

  /** Schema file → record, plus a valid sample. New event types are added here in their phase. */
  static final Map<String, Object> SAMPLES = new LinkedHashMap<>();

  static {
    SAMPLES.put("demo.note.created.v1.json", new NoteCreatedV1(UUID.fromString("0192f1e2-0000-7000-8000-000000000001"),
        "lunch", 25_000, "2026-09-27T10:00:00Z"));
    SAMPLES.put("demo.note.renamed.v1.json", new NoteRenamedV1(UUID.fromString("0192f1e2-0000-7000-8000-000000000001"),
        "dinner"));
    UUID account = UUID.fromString("0192f1e2-0000-7000-8000-0000000000a1");
    UUID session = UUID.fromString("0192f1e2-0000-7000-8000-0000000000c1");
    SAMPLES.put("identity.account.registered.v1.json", new AccountRegisteredV1(account, "Asia/Kolkata",
        "2026-10-17T10:00:00Z"));
    SAMPLES.put("identity.account.verified.v1.json", new AccountVerifiedV1(account, "2026-10-17T10:05:00Z"));
    SAMPLES.put("identity.session.revoked.v1.json", new SessionRevokedV1(session, "logout", "2026-10-17T11:00:00Z"));
    SAMPLES.put("identity.security.alert.v1.json", new SecurityAlertV1(SecurityAlertV1.REFRESH_TOKEN_REUSE, session,
        "2026-10-17T11:00:01Z"));
    SAMPLES.put("identity.security.alert.v2.json", new SecurityAlertV2(SecurityAlertV2.ACCOUNT_LOCKED, account,
        "2026-10-17T11:00:02Z"));
    SAMPLES.put("identity.admin.action.v1.json", new AdminActionV1("BREAK_GLASS_STARTED",
        UUID.fromString("0192f1e2-0000-7000-8000-0000000000d1"), "elevation", session,
        "Production incident INC-42: refund batch stuck", "2026-10-17T11:00:03Z"));
    SAMPLES.put("gateway.activity.recorded.v1.json", new ActivityRecordedV1("01a148e3-d593-7fda-ae19-6f623a15b6ea",
        "ledger", "/v1/expenses/**", "read", "GET", 200, 12, "2026-10-17T12:00:00Z"));
    UUID expense = UUID.fromString("0192f1e2-0000-7000-8000-0000000000e1");
    ExpenseStateV1 before = new ExpenseStateV1(45_000, "2026-10-17", "food.dining", "card",
        UUID.fromString("0192f1e2-0000-7000-8000-0000000000b1"), null, "Cafe Mocha", "2026-10-17T08:30:00Z");
    ExpenseStateV1 after = new ExpenseStateV1(52_000, "2026-10-16", "food.delivery", "upi", null,
        UUID.fromString("0192f1e2-0000-7000-8000-0000000000f1"), "Swiggy", "2026-10-16T19:30:00Z");
    SAMPLES.put("ledger.expense.created.v1.json", new ExpenseChangedV1(expense, 0, null, after, "2026-10-17T12:00:00Z"));
    SAMPLES.put("ledger.expense.updated.v1.json", new ExpenseChangedV1(expense, 1, before, after, "2026-10-17T12:00:01Z"));
    SAMPLES.put("ledger.expense.deleted.v1.json", new ExpenseChangedV1(expense, 2, after, null, "2026-10-17T12:00:02Z"));
    SAMPLES.put("ledger.expense.restored.v1.json", new ExpenseChangedV1(expense, 3, null, after,
        "2026-10-17T12:00:03Z"));
    UUID card = UUID.fromString("01941f29-7c00-79b6-9690-fe36f7f70c1b");
    java.util.Map<String, Object> snap = java.util.Map.of("id", card.toString(), "slug", "mock-saffron-millennia",
        "rules", java.util.List.of());
    SAMPLES.put("catalog.card.published.v1.json", new CardPublishedV1(card, "mock-saffron-millennia", "published", 2,
        "2026-10-17T12:00:00Z", null, snap));
    SAMPLES.put("catalog.card.retired.v1.json", new CardPublishedV1(card, "mock-saffron-millennia", "retired", 2,
        "2026-10-17T12:00:00Z", "2026-10-18T12:00:00Z", snap));
    SAMPLES.put("catalog.offer.changed.v1.json", new OfferChangedV1(
        UUID.fromString("0192f1e2-0000-7000-8000-0000000000a3"), "active",
        UUID.fromString("01941f29-7c00-7000-8000-0000000000a1"), card, null, "food.delivery", "10% off food",
        "pct", 1000, null, 15_000L, 50_000, "2026-10-01T00:00:00Z", null, true, 0, "2026-10-17T12:00:00Z"));
    UUID userCard = UUID.fromString("0192f1e2-0000-7000-8000-0000000000c9");
    for (String a : new String[] {"added", "updated", "removed"}) {
      SAMPLES.put("portfolio.card." + a + ".v1.json", new CardChangedV1(userCard, card, "Millennia", "4321", "visa",
          31, a.equals("removed") ? "removed" : "active", a.equals("added") ? 0 : 1, "2026-10-22T10:00:00Z"));
    }
    SAMPLES.put("portfolio.transaction.recorded.v1.json", new TransactionRecordedV1(
        UUID.fromString("0192f1e2-0000-7000-8000-0000000000d9"), userCard,
        UUID.fromString("0192f1e2-0000-7000-8000-0000000000e9"), "2026-10-21T09:00:00Z", 64_900, "debit", "purchase",
        null, "food.delivery", "cashback", 3_245, 0, "2026-10-22T10:00:01Z"));
    SAMPLES.put("portfolio.link.confirmed.v1.json", new LinkConfirmedV1(
        UUID.fromString("0192f1e2-0000-7000-8000-0000000000f9"), expense,
        UUID.fromString("0192f1e2-0000-7000-8000-0000000000d9"), userCard, "auto", "2026-10-22T10:00:02Z"));
    UUID upload = UUID.fromString("0192f1e2-0000-7000-8000-0000000000f1");
    UUID uploadCard = UUID.fromString("0192f1e2-0000-7000-8000-0000000000b1");
    SAMPLES.put("portfolio.statement.submitted.v1.json", new StatementSubmittedV1(upload, uploadCard, "4321",
        "http://127.0.0.1:8333/dexter-statements/statements/a/u.pdf?X-Amz-Signature=x", "a".repeat(64), 48_213,
        "application/pdf", "2026-10-26T16:00:00Z", "2026-10-26T10:00:00Z"));
    SAMPLES.put("ai-parser.statement.parsed.v1.json", new StatementParsedV1(upload, uploadCard, "parsed", null, null,
        "rules-1", "saffron-v1", "MOCK_SAFFRON", "4321", "2026-09-06", "2026-10-05", "2026-10-25", 1_234_567L,
        61_729L, 1, java.util.List.of(new StatementParsedV1.Line(1, "2026-09-07", "SWIGGY*BANGALORE", 123_450, "debit",
            "purchase"), new StatementParsedV1.Line(2, "2026-09-08", "PAYMENT RECEIVED XXXX XXXX XXXX 1111 THANK YOU",
            500_000, "credit", "payment")), "2026-10-26T10:00:05Z"));
    SAMPLES.put("ledger.import.completed.v1.json", new ImportCompletedV1(
        UUID.fromString("0192f1e2-0000-7000-8000-0000000000f2"), "completed", 120, 118, 20, 2, null,
        "2026-10-17T12:05:00Z"));
    // P13 planning
    SAMPLES.put("planning.budget.threshold-crossed.v1.json", new BudgetThresholdCrossedV1(
        UUID.fromString("0192f1e2-0000-7000-8000-000000000d01"), "Eating out", "monthly", "food.dining",
        "2026-10-01", 80, 1_000_000L, 812_500L, 8125, "2026-10-21T09:15:00Z"));
    SAMPLES.put("planning.liability.changed.v1.json", new LiabilityChangedV1(
        UUID.fromString("0192f1e2-0000-7000-8000-000000000d02"), "loan", "HDFC Bank", 10_000_000L, 1200, 12,
        "2026-11-01", 888_488L, "active", 0, "2026-10-21T09:20:00Z"));
  }

  static JsonNode schema(String file) throws IOException {
    try (InputStream in = SchemaContractTest.class.getResourceAsStream("/events/" + file)) {
      assertThat(in).as("schema on classpath: " + file).isNotNull();
      return MAPPER.readTree(in.readAllBytes());
    }
  }

  @Test
  void recordsMatchTheirSchemas() throws IOException {
    for (Map.Entry<String, Object> e : SAMPLES.entrySet()) {
      JsonNode schema = schema(e.getKey());
      Class<?> rec = e.getValue().getClass();
      Set<String> components = new HashSet<>();
      for (RecordComponent c : rec.getRecordComponents()) {
        components.add(c.getName());
        JsonNode prop = schema.get("properties").get(c.getName());
        assertThat(prop).as(e.getKey() + " has property " + c.getName()).isNotNull();
        assertThat(jsonType(c.getType())).as(e.getKey() + "." + c.getName()).isEqualTo(declaredType(prop));
      }
      assertThat(schema.get("properties").propertyNames()).as(e.getKey()).containsExactlyInAnyOrderElementsOf(components);
      Set<String> required = new HashSet<>();
      schema.get("required").forEach(r -> required.add(r.stringValue()));
      assertThat(required).as(e.getKey() + " required = all components (no optional fields in v1)")
          .isEqualTo(components);
      assertThat(MiniSchema.validate(schema, MAPPER.valueToTree(e.getValue()))).as(e.getKey()).isEmpty();
    }
  }

  @Test
  void validatorCatchesViolations() throws IOException {
    JsonNode schema = schema("demo.note.created.v1.json");
    JsonNode bad = MAPPER.readTree(
        "{\"noteId\":\"nope\",\"title\":\"\",\"amountPaise\":-1,\"createdAt\":\"x\",\"extra\":1}");
    assertThat(MiniSchema.validate(schema, bad)).hasSize(5);
  }

  @Test
  void realEnvelopesMatchTheEnvelopeSchema() throws IOException {
    EnvelopeCodec codec = new EnvelopeCodec(MAPPER);
    JsonNode schema = schema("envelope.v1.json");
    for (UUID account : new UUID[] {UUID.fromString("0000000a-0000-7000-8000-000000000001"), null}) {
      EventEnvelope e = new EventEnvelope(UUID.fromString("0192f1e2-0000-7000-8000-000000000009"), NoteCreatedV1.TYPE,
          NoteCreatedV1.VERSION, "demo", Instant.parse("2026-09-27T10:00:00Z"), account,
          UUID.fromString("0192f1e2-0000-7000-8000-000000000001"), 1, null,
          MAPPER.valueToTree(SAMPLES.get("demo.note.created.v1.json")));
      assertThat(MiniSchema.validate(schema, MAPPER.readTree(codec.encode(e)))).isEmpty();
    }
  }

  /** The schema type; a nullable field is {@code ["<type>", "null"]}. */
  static String declaredType(JsonNode prop) {
    JsonNode t = prop.get("type");
    if (t.isArray()) {
      for (JsonNode x : t) {
        if (!"null".equals(x.stringValue())) {
          return x.stringValue();
        }
      }
    }
    return t.stringValue();
  }

  static String jsonType(Class<?> t) {
    if (t == UUID.class || t == String.class) {
      return "string";
    }
    if (t == long.class || t == int.class || t == Long.class || t == Integer.class) {
      return "integer";
    }
    if (t == boolean.class || t == Boolean.class) {
      return "boolean";
    }
    if (List.class.isAssignableFrom(t)) {
      return "array";
    }
    return "object";
  }
}
