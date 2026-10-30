package in.dexter.planning.app;

import in.dexter.contracts.events.planning.LiabilityChangedV1;
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
import in.dexter.events.OutboxWriter;
import in.dexter.idempotency.ApiResponse;
import in.dexter.idempotency.IdempotencyEngine;
import in.dexter.idempotency.IdempotentRequest;
import in.dexter.idempotency.IdempotentResponse;
import in.dexter.planning.app.LiabilityStore.InstallmentRow;
import in.dexter.planning.app.LiabilityStore.LiabilityRow;
import in.dexter.planning.domain.LiabilityMath;
import in.dexter.planning.domain.PlanningErrors;
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
 * Liabilities (MASTER_PLAN P13): create (Idempotency-Key required; the instalment and the whole amortization
 * schedule are computed once by the shared EMI engine and stored), list, get, the schedule (keyset), merge-patch
 * with If-Match (lender, status) and DELETE = close. Every change publishes
 * {@code planning.liability.changed}.
 *
 * <p>The schedule is never rewritten: it is what the terms implied on the day the liability was recorded, so a
 * later rate change is a new liability rather than a silent re-rate.
 */
public final class LiabilityService {
  public static final String PATH = "/v1/liabilities";

  static final Set<String> KINDS = Set.of("loan", "card_emi", "bnpl", "other");
  static final Set<String> PATCHABLE = Set.of("lender", "status");

  /** POST /v1/liabilities body (also the Idempotency-Key fingerprint). */
  public record NewLiability(String kind, String lender, Long principalPaise, Integer aprBps, Integer tenureMonths,
      String startDate) {}

  public record LiabilityView(UUID id, String kind, String lender, long principalPaise, int aprBps,
      int tenureMonths, LocalDate startDate, LocalDate lastDueDate, long emiPaise, long totalInterestPaise,
      long totalPayablePaise, String status, Instant createdAt, Instant updatedAt, int version) {
    static LiabilityView of(LiabilityRow l) {
      return new LiabilityView(l.id(), l.kind(), l.lender(), l.principalPaise(), l.aprBps(), l.tenureMonths(),
          l.startDate(), l.startDate().plusMonths(l.tenureMonths() - 1L), l.emiPaise(), l.totalInterestPaise(),
          l.totalPayablePaise(), l.status(), l.createdAt(), l.updatedAt(), l.version());
    }
  }

  public record InstallmentView(int number, LocalDate dueOn, long principalPaise, long interestPaise,
      long totalPaise, long outstandingAfterPaise) {
    static InstallmentView of(InstallmentRow i) {
      return new InstallmentView(i.number(), i.dueOn(), i.principalPaise(), i.interestPaise(), i.totalPaise(),
          i.outstandingAfterPaise());
    }
  }

  private final TenantTx tx;
  private final IdempotencyEngine idempotency;
  private final LiabilityStore liabilities;
  private final OutboxWriter outbox;
  private final ObjectMapper mapper;
  private final Clock clock;
  private final java.util.concurrent.atomic.AtomicBoolean failNext = new java.util.concurrent.atomic.AtomicBoolean();

  public LiabilityService(TenantTx tx, IdempotencyEngine idempotency, LiabilityStore liabilities,
      OutboxWriter outbox, ObjectMapper mapper, Clock clock) {
    this.tx = tx;
    this.idempotency = idempotency;
    this.liabilities = liabilities;
    this.outbox = outbox;
    this.mapper = mapper;
    this.clock = clock;
  }

  /** Test hook (IdempotencyContract): the next create fails after its business write and its event. */
  public void failNextCreate() {
    failNext.set(true);
  }

  public IdempotentResponse create(Tenant tenant, UUID key, NewLiability cmd) {
    if (cmd == null) {
      throw new ValidationException(List.of(new FieldError("body", "REQUIRED", "A liability")));
    }
    List<FieldError> e = new ArrayList<>();
    String kind = Inputs.text("kind", cmd.kind(), 20, true, e);
    if (kind != null && !KINDS.contains(kind)) {
      e.add(new FieldError("kind", "INVALID", "loan, card_emi, bnpl or other"));
    }
    String lender = Inputs.text("lender", cmd.lender(), 80, true, e);
    long principal = Inputs.amount("principalPaise", cmd.principalPaise(), e);
    int apr = Inputs.count("aprBps", cmd.aprBps(), 0, 6000, true, e);
    int tenure = Inputs.count("tenureMonths", cmd.tenureMonths(), 1, 480, true, e);
    LocalDate start = Inputs.date("startDate", cmd.startDate(), true, e);
    if (!e.isEmpty()) {
      throw new ValidationException(e);
    }
    LiabilityMath.Schedule schedule = LiabilityMath.schedule(principal, apr, tenure, start);
    IdempotentRequest fingerprint = IdempotentRequest.of("POST", PATH, cmd, mapper);
    return tx.write(tenant, () -> idempotency.execute(tenant, key, fingerprint, () -> {
      Instant now = now();
      LiabilityRow l = new LiabilityRow(Ids.newId(), tenant.accountId(), kind, lender, principal, apr, tenure,
          start, schedule.emiPaise(), schedule.totalInterestPaise(), schedule.totalPayablePaise(), "active",
          now, now, 0);
      List<InstallmentRow> rows = new ArrayList<>(schedule.installments().size());
      for (LiabilityMath.Installment i : schedule.installments()) {
        rows.add(new InstallmentRow(l.id(), i.number(), i.dueOn(), i.principalPaise(), i.interestPaise(),
            i.totalPaise(), i.outstandingAfterPaise()));
      }
      liabilities.insert(l, rows);
      publish(l, now);
      if (failNext.getAndSet(false)) {
        throw new IllegalStateException("injected failure after the business write");
      }
      return new ApiResponse(201, Map.of("Location", PATH + "/" + l.id(), "ETag", "\"0\""), LiabilityView.of(l));
    }));
  }

  public Page<LiabilityView> list(Tenant tenant, PageRequest page) {
    return tx.read(tenant, () -> liabilities.list(page)).map(LiabilityView::of);
  }

  public LiabilityView get(Tenant tenant, UUID id) {
    return tx.read(tenant, () -> liabilities.find(id).map(LiabilityView::of).orElseThrow(notFound()));
  }

  public Page<InstallmentView> schedule(Tenant tenant, UUID id, PageRequest page) {
    return tx.read(tenant, () -> {
      liabilities.find(id).orElseThrow(notFound());
      return liabilities.schedule(page, id).map(InstallmentView::of);
    });
  }

  /** Merge patch of {@code lender} and {@code status} (closed, from active). */
  public LiabilityView patch(Tenant tenant, UUID id, long expectedVersion, JsonNode patch) {
    if (patch == null || !patch.isObject()) {
      throw new ValidationException(List.of(new FieldError("body", "INVALID", "A JSON object (merge patch)")));
    }
    List<FieldError> unknown = new ArrayList<>();
    for (String name : patch.propertyNames()) {
      if (!PATCHABLE.contains(name)) {
        unknown.add(new FieldError(name, "UNKNOWN_FIELD", "Not patchable"));
      }
    }
    if (!unknown.isEmpty()) {
      throw new ValidationException(unknown);
    }
    return tx.write(tenant, () -> {
      LiabilityRow l = liabilities.lock(id).orElseThrow(notFound());
      if (l.version() != expectedVersion) {
        throw new VersionConflictException("Liability", expectedVersion);
      }
      List<FieldError> e = new ArrayList<>();
      String lender = patch.has("lender") ? Inputs.text("lender", text(patch.get("lender")), 80, true, e)
          : l.lender();
      String status = l.status();
      if (patch.has("status")) {
        String s = text(patch.get("status"));
        if (!Set.of("active", "closed").contains(s)) {
          e.add(new FieldError("status", "INVALID", "closed"));
        } else if (!s.equals(l.status()) && !"active".equals(l.status())) {
          throw new DexterException(PlanningErrors.LIABILITY_NOT_ACTIVE, "The liability is " + l.status());
        } else {
          status = s;
        }
      }
      if (!e.isEmpty()) {
        throw new ValidationException(e);
      }
      if (lender.equals(l.lender()) && status.equals(l.status())) {
        return LiabilityView.of(l);
      }
      Instant now = now();
      LiabilityRow updated = new LiabilityRow(l.id(), l.accountId(), l.kind(), lender, l.principalPaise(),
          l.aprBps(), l.tenureMonths(), l.startDate(), l.emiPaise(), l.totalInterestPaise(), l.totalPayablePaise(),
          status, l.createdAt(), now, l.version() + 1);
      liabilities.update(updated);
      publish(updated, now);
      return LiabilityView.of(updated);
    });
  }

  /** Closes an active liability; closing a closed one changes nothing. */
  public void close(Tenant tenant, UUID id) {
    tx.writeVoid(tenant, () -> {
      LiabilityRow l = liabilities.lock(id).orElseThrow(notFound());
      if ("closed".equals(l.status())) {
        return;
      }
      Instant now = now();
      LiabilityRow closed = new LiabilityRow(l.id(), l.accountId(), l.kind(), l.lender(), l.principalPaise(),
          l.aprBps(), l.tenureMonths(), l.startDate(), l.emiPaise(), l.totalInterestPaise(), l.totalPayablePaise(),
          "closed", l.createdAt(), now, l.version() + 1);
      liabilities.update(closed);
      publish(closed, now);
    });
  }

  private void publish(LiabilityRow l, Instant at) {
    outbox.append(PlanningEvents.LIABILITY_CHANGED, l.accountId(), l.id(), l.version(),
        new LiabilityChangedV1(l.id(), l.kind(), l.lender(), l.principalPaise(), l.aprBps(), l.tenureMonths(),
            l.startDate().toString(), l.emiPaise(), l.status(), l.version(), at.toString()));
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }

  private static java.util.function.Supplier<NotFoundException> notFound() {
    return () -> new NotFoundException("Liability");
  }

  private static String text(JsonNode n) {
    return n == null || n.isNull() ? null : n.isString() ? n.asString() : n.toString();
  }
}
