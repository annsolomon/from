package in.dexter.portfolio.app;

import in.dexter.core.error.DexterException;
import in.dexter.core.error.FieldError;
import in.dexter.core.error.NotFoundException;
import in.dexter.core.error.ValidationException;
import in.dexter.core.id.Ids;
import in.dexter.core.tenant.Tenant;
import in.dexter.db.page.Page;
import in.dexter.db.page.PageRequest;
import in.dexter.db.tenant.TenantTx;
import in.dexter.idempotency.ApiResponse;
import in.dexter.idempotency.IdempotencyEngine;
import in.dexter.idempotency.IdempotentRequest;
import in.dexter.idempotency.IdempotentResponse;
import in.dexter.portfolio.app.CardStore.CardRow;
import in.dexter.portfolio.app.RewardStore.Entry;
import in.dexter.portfolio.domain.BillingCycle;
import in.dexter.portfolio.domain.PointsBalance;
import in.dexter.portfolio.domain.PortfolioErrors;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;

/**
 * Reward points (MASTER_PLAN P11): the balance per card (FIFO by expiry, what expires within
 * {@value #EXPIRING_DAYS} days), the append-only ledger (keyset, newest first, each earn marked when it expires
 * soon), and manual redeem / adjust entries (Idempotency-Key required; a redemption never exceeds the available
 * points). Points are earned by recorded purchases ({@link TransactionService}).
 */
public final class RewardService {
  public static final String PATH = "/v1/rewards/ledger";
  public static final int EXPIRING_DAYS = 60;

  /** POST /v1/rewards/ledger body: redeem (points &gt; 0 are taken off) or adjust (signed). */
  public record NewEntry(UUID userCardId, String kind, Long points, String note) {}

  public record CardBalance(UUID userCardId, String nickname, long points, long valuePaise, long pointValueMilliPaise,
      long lapsed, long expiringSoon, List<PointsBalance.Expiring> expiring) {}

  public record Balance(long points, long valuePaise, long expiringSoon, int horizonDays, List<CardBalance> cards) {}

  public record EntryView(UUID id, UUID userCardId, String kind, long points, long valuePaise, LocalDate expiresOn,
      boolean expiringSoon, String sourceRef, UUID cardTxnId, String note, Instant createdAt) {}

  private final TenantTx tx;
  private final IdempotencyEngine idempotency;
  private final CardStore cards;
  private final RewardStore rewards;
  private final ObjectMapper mapper;
  private final Clock clock;

  public RewardService(TenantTx tx, IdempotencyEngine idempotency, CardStore cards, RewardStore rewards,
      ObjectMapper mapper, Clock clock) {
    this.tx = tx;
    this.idempotency = idempotency;
    this.cards = cards;
    this.rewards = rewards;
    this.mapper = mapper;
    this.clock = clock;
  }

  public Balance balance(Tenant tenant) {
    LocalDate today = today();
    return tx.read(tenant, () -> {
      List<CardBalance> out = new ArrayList<>();
      long points = 0;
      long value = 0;
      long soon = 0;
      for (UUID cardId : rewards.cardsWithPoints()) {
        CardRow c = cards.find(cardId).orElse(null);
        if (c == null) {
          continue; // a removed card: its points are no longer shown
        }
        CardBalance b = cardBalance(c, rewards.entries(cardId), today);
        out.add(b);
        points += b.points();
        value += b.valuePaise();
        soon += b.expiringSoon();
      }
      return new Balance(points, value, soon, EXPIRING_DAYS, out);
    });
  }

  private CardBalance cardBalance(CardRow c, List<Entry> entries, LocalDate today) {
    List<PointsBalance.Entry> es = new ArrayList<>();
    long pointValue = Terms.pointValue(Terms.of(cards.product(c.cardProductId()).orElse(null), mapper));
    for (Entry e : entries) {
      es.add(new PointsBalance.Entry(e.kind(), e.points(), e.expiresOn()));
      if (pointValue == 0 && "earn".equals(e.kind()) && e.points() > 0) {
        pointValue = e.valueMilliPaise() / e.points();
      }
    }
    PointsBalance.Result r = PointsBalance.of(es, today, EXPIRING_DAYS);
    return new CardBalance(c.id(), c.nickname(), r.available(), Math.max(0, r.available()) * pointValue / 1000,
        pointValue, r.lapsed(), r.expiringSoon(), r.soon());
  }

  public Page<EntryView> ledger(Tenant tenant, PageRequest page, UUID cardId) {
    LocalDate horizon = today().plusDays(EXPIRING_DAYS);
    LocalDate today = today();
    return tx.read(tenant, () -> rewards.list(page, cardId)).map(e -> new EntryView(e.id(), e.userCardId(), e.kind(),
        e.points(), e.valueMilliPaise() / 1000, e.expiresOn(), e.expiresOn() != null && !e.expiresOn().isBefore(today)
        && !e.expiresOn().isAfter(horizon), e.sourceRef(), e.cardTxnId(), e.note(), e.createdAt()));
  }

  public IdempotentResponse post(Tenant tenant, UUID key, NewEntry cmd) {
    if (cmd == null) {
      throw new ValidationException(List.of(new FieldError("body", "REQUIRED", "A ledger entry")));
    }
    List<FieldError> e = new ArrayList<>();
    if (cmd.userCardId() == null) {
      e.add(new FieldError("userCardId", "REQUIRED", "The card"));
    }
    if (!"redeem".equals(cmd.kind()) && !"adjust".equals(cmd.kind())) {
      e.add(new FieldError("kind", cmd.kind() == null ? "REQUIRED" : "INVALID", "redeem or adjust"));
    }
    long pts = cmd.points() == null ? 0 : cmd.points();
    if (pts == 0 || Math.abs(pts) > 100_000_000 || "redeem".equals(cmd.kind()) && pts < 0) {
      e.add(new FieldError("points", cmd.points() == null ? "REQUIRED" : "OUT_OF_RANGE",
          "Redeem: positive points to take off; adjust: non-zero, signed"));
    }
    String note = Inputs.text("note", cmd.note(), 200, "adjust".equals(cmd.kind()), e);
    if (!e.isEmpty()) {
      throw new ValidationException(e);
    }
    long signed = "redeem".equals(cmd.kind()) ? -pts : pts;
    IdempotentRequest fingerprint = IdempotentRequest.of("POST", PATH, cmd, mapper);
    return tx.write(tenant, () -> idempotency.execute(tenant, key, fingerprint, () -> {
      CardRow c = cards.lock(cmd.userCardId()).filter(x -> !x.removed()).orElseThrow(() -> new NotFoundException("card"));
      CardBalance b = cardBalance(c, rewards.entries(c.id()), today());
      if (signed < 0 && b.points() + signed < 0) {
        throw new DexterException(PortfolioErrors.INSUFFICIENT_POINTS, "Available: " + b.points() + " points");
      }
      Instant now = now();
      Entry entry = new Entry(Ids.newId(), tenant.accountId(), c.id(), cmd.kind(), signed,
          signed * b.pointValueMilliPaise(), null, null, null, note, now);
      rewards.append(entry);
      return new ApiResponse(201, Map.of("Location", PATH + "/" + entry.id()), new EntryView(entry.id(), c.id(),
          entry.kind(), signed, entry.valueMilliPaise() / 1000, null, false, null, null, note, now));
    }));
  }

  private LocalDate today() {
    return BillingCycle.day(clock.instant());
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }
}
