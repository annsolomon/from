package in.dexter.portfolio.app;

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
import in.dexter.idempotency.ApiResponse;
import in.dexter.idempotency.IdempotencyEngine;
import in.dexter.idempotency.IdempotentRequest;
import in.dexter.idempotency.IdempotentResponse;
import in.dexter.portfolio.app.CardStore.CardRow;
import in.dexter.portfolio.app.EmiStore.Installment;
import in.dexter.portfolio.app.EmiStore.Plan;
import in.dexter.portfolio.domain.BillingCycle;
import in.dexter.portfolio.domain.EmiMath;
import in.dexter.portfolio.domain.PortfolioErrors;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * EMI plans (MASTER_PLAN P11): create (Idempotency-Key required; the reducing-balance schedule is computed once and
 * stored), list and schedule (keyset), merge-patch with If-Match (description; status closed / foreclosed ends the
 * plan and cancels the unpaid instalments), DELETE = close.
 */
public final class EmiService {
  public static final String PATH = "/v1/emi-plans";

  /** POST /v1/emi-plans body (also the Idempotency-Key fingerprint). */
  public record NewPlan(UUID userCardId, UUID cardTxnId, String description, Long principalPaise,
      Integer annualRateBps, Integer tenureMonths, Long processingFeePaise, Integer gstBps, String firstDueDate) {}

  public record PlanView(UUID id, UUID userCardId, UUID cardTxnId, String description, long principalPaise,
      int annualRateBps, int tenureMonths, long processingFeePaise, int gstBps, LocalDate firstDueDate,
      LocalDate lastDueDate, long emiPaise, long totalInterestPaise, long totalGstPaise, long totalPayablePaise,
      String status, Instant createdAt, Instant updatedAt, int version) {
    static PlanView of(Plan p) {
      return new PlanView(p.id(), p.userCardId(), p.cardTxnId(), p.description(), p.principalPaise(),
          p.annualRateBps(), p.tenureMonths(), p.processingFeePaise(), p.gstBps(), p.firstDueDate(),
          p.firstDueDate().plusMonths(p.tenureMonths() - 1L), p.emiPaise(), p.totalInterestPaise(), p.totalGstPaise(),
          p.principalPaise() + p.totalInterestPaise() + p.totalGstPaise() + p.processingFeePaise(), p.status(),
          p.createdAt(), p.updatedAt(), p.version());
    }
  }

  public record InstallmentView(int number, LocalDate dueDate, long principalPaise, long interestPaise, long gstPaise,
      long totalPaise, long outstandingAfterPaise, String status) {
    static InstallmentView of(Installment i) {
      return new InstallmentView(i.number(), i.dueDate(), i.principalPaise(), i.interestPaise(), i.gstPaise(),
          i.totalPaise(), i.outstandingAfterPaise(), i.status());
    }
  }

  private final TenantTx tx;
  private final IdempotencyEngine idempotency;
  private final CardStore cards;
  private final TransactionStore txns;
  private final EmiStore plans;
  private final ObjectMapper mapper;
  private final Clock clock;

  public EmiService(TenantTx tx, IdempotencyEngine idempotency, CardStore cards, TransactionStore txns, EmiStore plans,
      ObjectMapper mapper, Clock clock) {
    this.tx = tx;
    this.idempotency = idempotency;
    this.cards = cards;
    this.txns = txns;
    this.plans = plans;
    this.mapper = mapper;
    this.clock = clock;
  }

  public IdempotentResponse create(Tenant tenant, UUID key, NewPlan cmd) {
    if (cmd == null) {
      throw new ValidationException(List.of(new FieldError("body", "REQUIRED", "An EMI plan")));
    }
    List<FieldError> e = new ArrayList<>();
    if (cmd.userCardId() == null) {
      e.add(new FieldError("userCardId", "REQUIRED", "The card the EMI is on"));
    }
    String description = Inputs.text("description", cmd.description(), 120, true, e);
    long principal = Inputs.amount("principalPaise", cmd.principalPaise(), e);
    if (cmd.principalPaise() != null && principal < 100_000) {
      e.add(new FieldError("principalPaise", "OUT_OF_RANGE", "At least Rs 1,000"));
    }
    int rate = cmd.annualRateBps() == null ? -1 : cmd.annualRateBps();
    if (rate < 0 || rate > 6000) {
      e.add(new FieldError("annualRateBps", cmd.annualRateBps() == null ? "REQUIRED" : "OUT_OF_RANGE",
          "0-6000 bps a year"));
    }
    int tenure = cmd.tenureMonths() == null ? 0 : cmd.tenureMonths();
    if (tenure < 1 || tenure > 84) {
      e.add(new FieldError("tenureMonths", cmd.tenureMonths() == null ? "REQUIRED" : "OUT_OF_RANGE", "1-84 months"));
    }
    long fee = cmd.processingFeePaise() == null ? 0 : cmd.processingFeePaise();
    if (fee < 0 || fee > principal) {
      e.add(new FieldError("processingFeePaise", "OUT_OF_RANGE", "0 to the principal"));
    }
    int gst = cmd.gstBps() == null ? 1800 : cmd.gstBps();
    if (gst < 0 || gst > 2800) {
      e.add(new FieldError("gstBps", "OUT_OF_RANGE", "0-2800 bps"));
    }
    LocalDate firstDue = Inputs.date("firstDueDate", cmd.firstDueDate(), e);
    if (!e.isEmpty()) {
      throw new ValidationException(e);
    }
    IdempotentRequest fingerprint = IdempotentRequest.of("POST", PATH, cmd, mapper);
    return tx.write(tenant, () -> idempotency.execute(tenant, key, fingerprint, () -> {
      CardRow card = cards.lock(cmd.userCardId()).filter(c -> !c.removed()).orElseThrow(() -> new ValidationException(
          List.of(new FieldError("userCardId", "UNKNOWN_CARD", "No such card"))));
      if (!"active".equals(card.status())) {
        throw new DexterException(PortfolioErrors.CARD_NOT_ACTIVE, "The card is " + card.status());
      }
      if (cmd.cardTxnId() != null) {
        TransactionStore.TxnRow t = txns.find(cmd.cardTxnId()).filter(x -> x.userCardId().equals(card.id()))
            .orElseThrow(() -> new ValidationException(List.of(new FieldError("cardTxnId", "UNKNOWN_TRANSACTION",
                "No such transaction on this card"))));
        if (!"debit".equals(t.direction())) {
          throw new ValidationException(List.of(new FieldError("cardTxnId", "NOT_A_DEBIT",
              "Only a debit converts to EMI")));
        }
      }
      LocalDate today = BillingCycle.day(clock.instant());
      LocalDate first = firstDue != null ? firstDue
          : BillingCycle.containing(today, card.statementDay(), card.dueOffsetDays()).dueDate();
      EmiMath.Schedule s = EmiMath.schedule(principal, rate, tenure, gst, fee, first);
      Instant now = now();
      Plan p = new Plan(Ids.newId(), tenant.accountId(), card.id(), cmd.cardTxnId(), description, principal, rate,
          tenure, fee, gst, first, s.emiPaise(), s.totalInterestPaise(), s.totalGstPaise(), "active", now, now, 0);
      List<Installment> rows = new ArrayList<>();
      for (EmiMath.Installment i : s.installments()) {
        rows.add(new Installment(p.id(), i.number(), i.dueDate(), i.principalPaise(), i.interestPaise(), i.gstPaise(),
            i.totalPaise(), i.outstandingAfterPaise(), "due"));
      }
      plans.insert(p, rows);
      return new ApiResponse(201, Map.of("Location", PATH + "/" + p.id(), "ETag", "\"0\""), PlanView.of(p));
    }));
  }

  public Page<PlanView> list(Tenant tenant, PageRequest page) {
    return tx.read(tenant, () -> plans.list(page)).map(PlanView::of);
  }

  public PlanView get(Tenant tenant, UUID id) {
    return tx.read(tenant, () -> plans.find(id).map(PlanView::of).orElseThrow(() -> new NotFoundException("EMI plan")));
  }

  public Page<InstallmentView> schedule(Tenant tenant, UUID id, PageRequest page) {
    return tx.read(tenant, () -> {
      plans.find(id).orElseThrow(() -> new NotFoundException("EMI plan"));
      return plans.schedule(page, id).map(InstallmentView::of);
    });
  }

  /** Merge patch of {@code description} and {@code status} (closed / foreclosed, from active). */
  public PlanView patch(Tenant tenant, UUID id, long expectedVersion, JsonNode patch) {
    if (patch == null || !patch.isObject()) {
      throw new ValidationException(List.of(new FieldError("body", "INVALID", "A JSON object (merge patch)")));
    }
    List<FieldError> e = new ArrayList<>();
    for (String name : patch.propertyNames()) {
      if (!Set.of("description", "status").contains(name)) {
        e.add(new FieldError(name, "UNKNOWN_FIELD", "Not patchable"));
      }
    }
    if (!e.isEmpty()) {
      throw new ValidationException(e);
    }
    return tx.write(tenant, () -> {
      Plan p = plans.lock(id).orElseThrow(() -> new NotFoundException("EMI plan"));
      if (p.version() != expectedVersion) {
        throw new VersionConflictException("EMI plan", expectedVersion);
      }
      String description = patch.has("description")
          ? Inputs.text("description", text(patch.get("description")), 120, true, e) : p.description();
      String status = p.status();
      if (patch.has("status")) {
        String s = text(patch.get("status"));
        if (!Set.of("active", "closed", "foreclosed").contains(s)) {
          e.add(new FieldError("status", "INVALID", "closed or foreclosed"));
        } else if (!s.equals(p.status()) && !"active".equals(p.status())) {
          throw new DexterException(PortfolioErrors.EMI_NOT_ACTIVE, "The plan is " + p.status());
        } else if ("active".equals(s) && !"active".equals(p.status())) {
          throw new DexterException(PortfolioErrors.EMI_NOT_ACTIVE, "An ended plan cannot be reopened");
        } else {
          status = s;
        }
      }
      if (!e.isEmpty()) {
        throw new ValidationException(e);
      }
      if (description.equals(p.description()) && status.equals(p.status())) {
        return PlanView.of(p);
      }
      return PlanView.of(save(p, description, status));
    });
  }

  /** Closes an active plan (unpaid instalments cancelled); closing an ended plan changes nothing. */
  public void close(Tenant tenant, UUID id) {
    tx.writeVoid(tenant, () -> {
      Plan p = plans.lock(id).orElseThrow(() -> new NotFoundException("EMI plan"));
      if ("active".equals(p.status())) {
        save(p, p.description(), "closed");
      }
    });
  }

  private Plan save(Plan p, String description, String status) {
    Plan u = new Plan(p.id(), p.accountId(), p.userCardId(), p.cardTxnId(), description, p.principalPaise(),
        p.annualRateBps(), p.tenureMonths(), p.processingFeePaise(), p.gstBps(), p.firstDueDate(), p.emiPaise(),
        p.totalInterestPaise(), p.totalGstPaise(), status, p.createdAt(), now(), p.version() + 1);
    plans.update(u);
    if (!"active".equals(status)) {
      plans.cancelRemaining(p.id());
    }
    return u;
  }

  private static String text(JsonNode n) {
    return n == null || n.isNull() ? null : n.isString() ? n.asString() : n.toString();
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }
}
