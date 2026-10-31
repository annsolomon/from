package in.dexter.portfolio.app;

import in.dexter.contracts.events.portfolio.TransactionRecordedV1;
import in.dexter.core.error.DexterException;
import in.dexter.core.error.FieldError;
import in.dexter.core.error.NotFoundException;
import in.dexter.core.error.ValidationException;
import in.dexter.core.id.Ids;
import in.dexter.core.tenant.Tenant;
import in.dexter.db.page.Page;
import in.dexter.db.page.PageRequest;
import in.dexter.db.tenant.TenantTx;
import in.dexter.events.OutboxWriter;
import in.dexter.idempotency.ApiResponse;
import in.dexter.idempotency.IdempotencyEngine;
import in.dexter.idempotency.IdempotentRequest;
import in.dexter.idempotency.IdempotentResponse;
import in.dexter.portfolio.app.CardStore.CardRow;
import in.dexter.portfolio.app.TransactionStore.StatementRow;
import in.dexter.portfolio.app.TransactionStore.TxnRow;
import in.dexter.portfolio.domain.BillingCycle;
import in.dexter.portfolio.domain.PortfolioErrors;
import in.dexter.portfolio.calc.RewardTerms;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import tools.jackson.databind.ObjectMapper;

/**
 * Card transactions (MASTER_PLAN P11). Recording one (Idempotency-Key required) is one transaction: the statement of
 * its cycle is found or opened and its totals move, the transaction row, the reward it earns on the card's catalog
 * terms (points into the points ledger, value into the month's savings), the month's savings row, the
 * {@code portfolio.transaction.recorded} event, and an auto-match suggestion when a ledger expense fits.
 */
public final class TransactionService {
  static final Set<String> KINDS = Set.of("purchase", "refund", "payment", "fee", "interest", "cashback", "emi");
  static final Set<String> DEBITS = Set.of("purchase", "fee", "interest", "emi");
  /** Earned points expire this long after the posting day (typical Indian card terms). */
  static final int POINTS_LIFETIME_MONTHS = 36;

  /** POST /v1/cards/{id}/transactions body (also the Idempotency-Key fingerprint). */
  public record NewTransaction(Instant postedAt, Long amountPaise, String kind, String description, UUID merchantId,
      String categoryPath, String clientRef) {}

  private final TenantTx tx;
  private final IdempotencyEngine idempotency;
  private final CardStore cards;
  private final TransactionStore txns;
  private final RewardStore rewards;
  private final Matcher matcher;
  private final OutboxWriter outbox;
  private final ObjectMapper mapper;
  private final Clock clock;
  private final AtomicBoolean failNext = new AtomicBoolean();

  public TransactionService(TenantTx tx, IdempotencyEngine idempotency, CardStore cards, TransactionStore txns,
      RewardStore rewards, Matcher matcher, OutboxWriter outbox, ObjectMapper mapper, Clock clock) {
    this.tx = tx;
    this.idempotency = idempotency;
    this.cards = cards;
    this.txns = txns;
    this.rewards = rewards;
    this.matcher = matcher;
    this.outbox = outbox;
    this.mapper = mapper;
    this.clock = clock;
  }

  /** Test hook (IdempotencyContract): the next record fails after its business write. */
  public void failNextRecord() {
    failNext.set(true);
  }

  public IdempotentResponse record(Tenant tenant, UUID cardId, UUID key, NewTransaction cmd) {
    if (cmd == null) {
      throw new ValidationException(List.of(new FieldError("body", "REQUIRED", "A transaction")));
    }
    List<FieldError> e = new ArrayList<>();
    if (cmd.postedAt() == null) {
      e.add(new FieldError("postedAt", "REQUIRED", "When it posted (ISO-8601 instant)"));
    } else if (cmd.postedAt().isAfter(clock.instant().plusSeconds(300))) {
      e.add(new FieldError("postedAt", "IN_FUTURE", "Not in the future"));
    }
    long amount = Inputs.amount("amountPaise", cmd.amountPaise(), e);
    String kind = cmd.kind() == null ? "purchase" : cmd.kind();
    if (!KINDS.contains(kind)) {
      e.add(new FieldError("kind", "INVALID", "purchase, refund, payment, fee, interest, cashback or emi"));
    }
    String description = Inputs.text("description", cmd.description(), 200, true, e);
    if (!Inputs.category(cmd.categoryPath())) {
      e.add(new FieldError("categoryPath", "INVALID", "Not a category path"));
    }
    String clientRef = Inputs.text("clientRef", cmd.clientRef(), 100, false, e);
    if (!e.isEmpty()) {
      throw new ValidationException(e);
    }
    Instant postedAt = cmd.postedAt().truncatedTo(ChronoUnit.MICROS);
    IdempotentRequest fingerprint = IdempotentRequest.of("POST", "/v1/cards/" + cardId + "/transactions", cmd, mapper);
    return tx.write(tenant, () -> idempotency.execute(tenant, key, fingerprint, () -> {
      CardRow card = cards.lock(cardId).filter(c -> !c.removed()).orElseThrow(() -> new NotFoundException("card"));
      if (!"active".equals(card.status())) {
        throw new DexterException(PortfolioErrors.CARD_NOT_ACTIVE, "The card is " + card.status());
      }
      if (clientRef != null && txns.idByClientRef(clientRef).isPresent()) {
        throw new DexterException(PortfolioErrors.CLIENT_REF_TAKEN, "A transaction already has this clientRef");
      }
      Recorded r = write(tenant, card, postedAt, amount, kind, description, cmd.merchantId(), cmd.categoryPath(),
          clientRef, "manual", now());
      if (failNext.getAndSet(false)) {
        throw new IllegalStateException("injected failure after the business write");
      }
      TxnView.Reward reward = new TxnView.Reward(r.earned().rewardType(), r.earned().rateBps(),
          r.earned().valuePaise(), r.earned().points());
      return new ApiResponse(201, Map.of("Location", "/v1/cards/" + card.id() + "/transactions/" + r.txn().id(),
          "ETag", "\"0\""), TxnView.of(r.txn(), reward));
    }));
  }

  /** What {@link #write} recorded: the transaction and what it earned. */
  record Recorded(TxnRow txn, RewardTerms.Earning earned) {}

  /**
   * Writes one card transaction inside the caller's write transaction; the card is locked and active, the input is
   * valid and the client reference (if any) is free. Moves the cycle's statement, earns rewards on purchases, adds to
   * the month's savings, appends {@code portfolio.transaction.recorded} and runs the auto-matcher. Manual records
   * and confirmed statement lines (P12, source {@code statement}) both come through here.
   */
  Recorded write(Tenant tenant, CardRow card, Instant postedAt, long amount, String kind, String description,
      UUID merchantId, String categoryPath, String clientRef, String source, Instant now) {
    String direction = DEBITS.contains(kind) ? "debit" : "credit";
    StatementRow st = statementFor(card, BillingCycle.day(postedAt), now);
    boolean debit = "debit".equals(direction);
    txns.addToStatement(st.id(), debit ? amount : 0, debit ? 0 : amount, now);
    TxnRow t = new TxnRow(Ids.newId(), tenant.accountId(), card.id(), st.id(), postedAt, amount, direction, kind,
        description, merchantId, categoryPath, null, clientRef, source, now, 0);
    txns.insert(t);
    RewardTerms.Earning earned = "purchase".equals(kind)
        ? Terms.of(cards.product(card.cardProductId()).orElse(null), mapper).earn(amount, categoryPath, merchantId)
        : RewardTerms.Earning.NONE;
    if (earned.points() > 0) {
      rewards.append(new RewardStore.Entry(Ids.newId(), tenant.accountId(), card.id(), "earn", earned.points(),
          earned.valueMilliPaise(), BillingCycle.day(postedAt).plusMonths(POINTS_LIFETIME_MONTHS), "txn:" + t.id(),
          t.id(), null, now));
    }
    long spend = "purchase".equals(kind) ? amount : "refund".equals(kind) ? -amount : 0;
    long fees = "fee".equals(kind) || "interest".equals(kind) ? amount : 0;
    txns.addSavings(tenant.accountId(), card.id(), BillingCycle.day(postedAt).withDayOfMonth(1), spend, 1,
        earned.valuePaise(), fees);
    outbox.append(PortfolioEvents.TRANSACTION_RECORDED, tenant.accountId(), t.id(), 0, new TransactionRecordedV1(
        t.id(), card.id(), st.id(), postedAt.toString(), amount, direction, kind, merchantId, categoryPath,
        earned.rewardType(), earned.valuePaise(), earned.points(), now.toString()));
    matcher.forTransaction(t, now);
    return new Recorded(t, earned);
  }

  /** The cycle's statement, opened on first use (a concurrent opener wins the insert; we lock its row). */
  private StatementRow statementFor(CardRow card, LocalDate day, Instant now) {
    BillingCycle c = BillingCycle.containing(day, card.statementDay(), card.dueOffsetDays());
    return txns.lockStatement(card.id(), c.periodStart()).orElseGet(() -> {
      txns.insertStatement(new StatementRow(Ids.newId(), card.accountId(), card.id(), c.periodStart(), c.periodEnd(),
          c.dueDate(), 0, 0, 0, 0, 0, "open", now, now, 0));
      return txns.lockStatement(card.id(), c.periodStart()).orElseThrow();
    });
  }

  public Page<TxnView> list(Tenant tenant, UUID cardId, PageRequest page) {
    return tx.read(tenant, () -> {
      cards.find(cardId).orElseThrow(() -> new NotFoundException("card"));
      return txns.list(page, cardId).map(t -> TxnView.of(t, null));
    });
  }

  public TxnView get(Tenant tenant, UUID cardId, UUID id) {
    return tx.read(tenant, () -> txns.find(id).filter(t -> t.userCardId().equals(cardId)).map(t -> TxnView.of(t, null))
        .orElseThrow(() -> new NotFoundException("transaction")));
  }

  public Page<StatementView> statements(Tenant tenant, UUID cardId, PageRequest page) {
    return tx.read(tenant, () -> {
      cards.find(cardId).orElseThrow(() -> new NotFoundException("card"));
      return txns.statements(page, cardId).map(StatementView::of);
    });
  }

  /** The closer job: generates the statements of ended cycles (all tenants, owner function). */
  public int closeEndedStatements(Tenant system, int batch) {
    LocalDate today = BillingCycle.day(clock.instant());
    int total = 0;
    int n;
    do {
      n = tx.write(system, () -> txns.closeEndedStatements(today, batch));
      total += n;
    } while (n == batch);
    return total;
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }
}
