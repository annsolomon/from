package in.dexter.portfolio.app;

import in.dexter.contracts.events.portfolio.CardChangedV1;
import in.dexter.core.error.DexterException;
import in.dexter.core.error.FieldError;
import in.dexter.core.error.NotFoundException;
import in.dexter.core.error.ValidationException;
import in.dexter.core.error.VersionConflictException;
import in.dexter.core.id.Ids;
import in.dexter.core.tenant.Tenant;
import in.dexter.db.page.Page;
import in.dexter.db.page.PageRequest;
import in.dexter.db.tenant.TenantTx;
import in.dexter.events.EventType;
import in.dexter.events.OutboxWriter;
import in.dexter.idempotency.ApiResponse;
import in.dexter.idempotency.IdempotencyEngine;
import in.dexter.idempotency.IdempotentRequest;
import in.dexter.idempotency.IdempotentResponse;
import in.dexter.portfolio.app.CardStore.CardRow;
import in.dexter.portfolio.app.CardStore.Product;
import in.dexter.portfolio.domain.BillingCycle;
import in.dexter.portfolio.domain.PortfolioErrors;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * User cards (MASTER_PLAN P11): add (Idempotency-Key required; the product must be in the catalog projection and
 * published), list (keyset, newest first), read, merge-patch with If-Match, remove (soft, idempotent). Every change
 * writes {@code portfolio.card.*} in the same transaction.
 */
public final class CardService {
  public static final String PATH = "/v1/cards";
  static final Set<String> PATCHABLE = Set.of("nickname", "statementDay", "dueOffsetDays", "creditLimitPaise",
      "openedOn", "status");

  /** POST /v1/cards body (also the Idempotency-Key fingerprint). */
  public record NewCard(UUID cardProductId, String nickname, String last4, Integer statementDay, Integer dueOffsetDays,
      Long creditLimitPaise, String openedOn) {}

  private final TenantTx tx;
  private final IdempotencyEngine idempotency;
  private final CardStore cards;
  private final OutboxWriter outbox;
  private final java.util.concurrent.atomic.AtomicBoolean failNext = new java.util.concurrent.atomic.AtomicBoolean();
  private final ObjectMapper mapper;
  private final Clock clock;

  public CardService(TenantTx tx, IdempotencyEngine idempotency, CardStore cards, OutboxWriter outbox,
      ObjectMapper mapper, Clock clock) {
    this.tx = tx;
    this.idempotency = idempotency;
    this.cards = cards;
    this.outbox = outbox;
    this.mapper = mapper;
    this.clock = clock;
  }

  /** Test hook (IdempotencyContract): the next create fails after its business write. */
  public void failNextCreate() {
    failNext.set(true);
  }

  public IdempotentResponse create(Tenant tenant, UUID key, NewCard cmd) {
    if (cmd == null) {
      throw new ValidationException(List.of(new FieldError("body", "REQUIRED", "A card")));
    }
    List<FieldError> e = new ArrayList<>();
    if (cmd.cardProductId() == null) {
      e.add(new FieldError("cardProductId", "REQUIRED", "A catalog card product id (GET /v1/catalog/cards)"));
    }
    String last4 = cmd.last4() == null ? null : cmd.last4().strip();
    if (last4 == null || !last4.matches("^[0-9]{4}$")) {
      e.add(new FieldError("last4", last4 == null ? "REQUIRED" : "INVALID", "The card's last four digits"));
    }
    String nickname = Inputs.text("nickname", cmd.nickname(), 60, false, e);
    int statementDay = cmd.statementDay() == null ? 0 : cmd.statementDay();
    if (!BillingCycle.validStatementDay(statementDay)) {
      e.add(new FieldError("statementDay", cmd.statementDay() == null ? "REQUIRED" : "INVALID",
          "1-28, or 31 for the last day of the month"));
    }
    int dueOffset = cmd.dueOffsetDays() == null ? 20 : cmd.dueOffsetDays();
    if (dueOffset < 10 || dueOffset > 30) {
      e.add(new FieldError("dueOffsetDays", "INVALID", "10-30 days after the statement"));
    }
    if (cmd.creditLimitPaise() != null && (cmd.creditLimitPaise() < 1 || cmd.creditLimitPaise() > Inputs.MAX_AMOUNT)) {
      e.add(new FieldError("creditLimitPaise", "OUT_OF_RANGE", "Positive paise"));
    }
    LocalDate openedOn = Inputs.date("openedOn", cmd.openedOn(), e);
    if (openedOn != null && openedOn.isAfter(today())) {
      e.add(new FieldError("openedOn", "IN_FUTURE", "Not after today"));
    }
    if (!e.isEmpty()) {
      throw new ValidationException(e);
    }
    IdempotentRequest fingerprint = IdempotentRequest.of("POST", PATH, cmd, mapper);
    return tx.write(tenant, () -> idempotency.execute(tenant, key, fingerprint, () -> {
      Product p = cards.product(cmd.cardProductId()).orElseThrow(() -> new ValidationException(List.of(
          new FieldError("cardProductId", "UNKNOWN_CARD_PRODUCT", "No such card in the catalog"))));
      if (!"published".equals(p.status())) {
        throw new ValidationException(List.of(new FieldError("cardProductId", "PRODUCT_RETIRED",
            "The card product is retired")));
      }
      if (cards.exists(p.id(), last4, null)) {
        throw new DexterException(PortfolioErrors.CARD_EXISTS, "This card (" + p.name() + " ending " + last4
            + ") is already in your portfolio");
      }
      Instant now = now();
      CardRow c = new CardRow(Ids.newId(), tenant.accountId(), p.id(), nickname == null ? display(p) : nickname, last4,
          p.network(), statementDay, dueOffset, cmd.creditLimitPaise(), openedOn, "active", now, now, null, 0);
      cards.insert(c);
      event(PortfolioEvents.CARD_ADDED, c, now);
      if (failNext.getAndSet(false)) {
        throw new IllegalStateException("injected failure after the business write");
      }
      return new ApiResponse(201, Map.of("Location", PATH + "/" + c.id(), "ETag", "\"0\""),
          CardView.of(c, p, today()));
    }));
  }

  public Page<CardView> list(Tenant tenant, PageRequest page) {
    LocalDate today = today();
    return tx.read(tenant, () -> {
      Map<UUID, Product> products = new HashMap<>();
      return cards.list(page).map(c -> CardView.of(c,
          products.computeIfAbsent(c.cardProductId(), id -> cards.product(id).orElse(null)), today));
    });
  }

  public CardView get(Tenant tenant, UUID id) {
    return tx.read(tenant, () -> {
      CardRow c = cards.find(id).orElseThrow(() -> new NotFoundException("card"));
      return CardView.of(c, cards.product(c.cardProductId()).orElse(null), today());
    });
  }

  /** JSON merge patch of nickname, statementDay, dueOffsetDays, creditLimitPaise, openedOn, status (active/closed). */
  public CardView patch(Tenant tenant, UUID id, long expectedVersion, JsonNode patch) {
    if (patch == null || !patch.isObject()) {
      throw new ValidationException(List.of(new FieldError("body", "INVALID", "A JSON object (merge patch)")));
    }
    List<FieldError> e = new ArrayList<>();
    for (String name : patch.propertyNames()) {
      if (!PATCHABLE.contains(name)) {
        e.add(new FieldError(name, "UNKNOWN_FIELD", "Not patchable"));
      }
    }
    if (!e.isEmpty()) {
      throw new ValidationException(e);
    }
    return tx.write(tenant, () -> {
      CardRow c = cards.lock(id).filter(x -> !x.removed()).orElseThrow(() -> new NotFoundException("card"));
      if (c.version() != expectedVersion) {
        throw new VersionConflictException("card", expectedVersion);
      }
      String nickname = c.nickname();
      if (patch.has("nickname")) {
        nickname = Inputs.text("nickname", str(patch.get("nickname")), 60, true, e);
      }
      int statementDay = c.statementDay();
      if (patch.has("statementDay")) {
        JsonNode n = patch.get("statementDay");
        if (!n.isIntegralNumber() || !BillingCycle.validStatementDay(n.asInt())) {
          e.add(new FieldError("statementDay", "INVALID", "1-28, or 31 for the last day of the month"));
        } else {
          statementDay = n.asInt();
        }
      }
      int dueOffset = c.dueOffsetDays();
      if (patch.has("dueOffsetDays")) {
        JsonNode n = patch.get("dueOffsetDays");
        if (!n.isIntegralNumber() || n.asInt() < 10 || n.asInt() > 30) {
          e.add(new FieldError("dueOffsetDays", "INVALID", "10-30 days after the statement"));
        } else {
          dueOffset = n.asInt();
        }
      }
      Long limit = c.creditLimitPaise();
      if (patch.has("creditLimitPaise")) {
        JsonNode n = patch.get("creditLimitPaise");
        if (n.isNull()) {
          limit = null;
        } else if (!n.isIntegralNumber() || n.asLong() < 1 || n.asLong() > Inputs.MAX_AMOUNT) {
          e.add(new FieldError("creditLimitPaise", "OUT_OF_RANGE", "Positive paise"));
        } else {
          limit = n.asLong();
        }
      }
      LocalDate openedOn = c.openedOn();
      if (patch.has("openedOn")) {
        openedOn = Inputs.date("openedOn", str(patch.get("openedOn")), e);
      }
      String status = c.status();
      if (patch.has("status")) {
        String s = str(patch.get("status"));
        if (!"active".equals(s) && !"closed".equals(s)) {
          e.add(new FieldError("status", "INVALID", "active or closed (DELETE removes a card)"));
        } else {
          status = s;
        }
      }
      if (!e.isEmpty()) {
        throw new ValidationException(e);
      }
      CardRow after = new CardRow(c.id(), c.accountId(), c.cardProductId(), nickname, c.last4(), c.network(),
          statementDay, dueOffset, limit, openedOn, status, c.createdAt(), c.updatedAt(), null, c.version());
      if (sameContent(c, after)) {
        return CardView.of(c, cards.product(c.cardProductId()).orElse(null), today());
      }
      Instant now = now();
      after = new CardRow(c.id(), c.accountId(), c.cardProductId(), nickname, c.last4(), c.network(), statementDay,
          dueOffset, limit, openedOn, status, c.createdAt(), now, null, c.version() + 1);
      cards.update(after);
      event(PortfolioEvents.CARD_UPDATED, after, now);
      return CardView.of(after, cards.product(c.cardProductId()).orElse(null), today());
    });
  }

  /** Soft remove; removing a removed card changes nothing. */
  public void remove(Tenant tenant, UUID id) {
    tx.writeVoid(tenant, () -> {
      CardRow c = cards.lock(id).orElseThrow(() -> new NotFoundException("card"));
      if (c.removed()) {
        return;
      }
      Instant now = now();
      CardRow after = new CardRow(c.id(), c.accountId(), c.cardProductId(), c.nickname(), c.last4(), c.network(),
          c.statementDay(), c.dueOffsetDays(), c.creditLimitPaise(), c.openedOn(), "removed", c.createdAt(), now, now,
          c.version() + 1);
      cards.update(after);
      event(PortfolioEvents.CARD_REMOVED, after, now);
    });
  }

  static boolean sameContent(CardRow a, CardRow b) {
    return a.nickname().equals(b.nickname()) && a.statementDay() == b.statementDay()
        && a.dueOffsetDays() == b.dueOffsetDays() && java.util.Objects.equals(a.creditLimitPaise(), b.creditLimitPaise())
        && java.util.Objects.equals(a.openedOn(), b.openedOn()) && a.status().equals(b.status());
  }

  /** "MOCK_Saffron Millennia Cashback" → "Saffron Millennia Cashback" (the seed's nickname rule). */
  static String display(Product p) {
    String n = p.name().startsWith("MOCK_") ? p.name().substring(5) : p.name();
    return n.length() > 60 ? n.substring(0, 60) : n;
  }

  private void event(EventType<CardChangedV1> type, CardRow c, Instant now) {
    outbox.append(type, c.accountId(), c.id(), c.version(), new CardChangedV1(c.id(), c.cardProductId(), c.nickname(),
        c.last4(), c.network(), c.statementDay(), c.status(), c.version(), now.toString()));
  }

  private static String str(JsonNode n) {
    return n == null || n.isNull() ? null : n.isString() ? n.asString() : n.toString();
  }

  private LocalDate today() {
    return BillingCycle.day(clock.instant());
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }
}
