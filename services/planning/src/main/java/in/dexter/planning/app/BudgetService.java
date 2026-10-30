package in.dexter.planning.app;

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
import in.dexter.planning.app.BudgetStore.BudgetRow;
import in.dexter.planning.app.BudgetStore.SpendRow;
import in.dexter.planning.domain.BudgetMath;
import in.dexter.planning.domain.BudgetPeriods;
import in.dexter.planning.domain.BudgetPeriods.Period;
import in.dexter.planning.domain.PlanningErrors;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Budgets (MASTER_PLAN P13): create (Idempotency-Key required), list and get, merge-patch with If-Match, DELETE =
 * end, the status of every active budget's current period, and one budget's period history (keyset).
 *
 * <p>A period's spend is not computed here — it is maintained by {@link SpendService} from ledger expense events.
 * This service reads it and adds the arithmetic a caller needs: how much is left, the run-rate projection for the
 * rest of the period, and which alert thresholds the spend has reached.
 */
public final class BudgetService {
  public static final String PATH = "/v1/budgets";

  /** Local days (Asia/Kolkata): the same zone the ledger stamps an expense's day with. */
  public static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

  static final Set<String> PATCHABLE = Set.of("name", "limitPaise", "rollover", "alertThresholds", "endsOn",
      "status");

  /** POST /v1/budgets body (also the Idempotency-Key fingerprint). */
  public record NewBudget(String name, String period, String categoryPath, Long limitPaise, Boolean rollover,
      List<Integer> alertThresholds, String startsOn, String endsOn) {}

  public record BudgetView(UUID id, String name, String period, String categoryPath, long limitPaise,
      boolean rollover, List<Integer> alertThresholds, LocalDate startsOn, LocalDate endsOn, String status,
      Instant createdAt, Instant updatedAt, int version) {
    static BudgetView of(BudgetRow b) {
      return new BudgetView(b.id(), b.name(), b.period(), b.categoryPath(), b.limitPaise(), b.rollover(),
          b.alertThresholds(), b.startsOn(), b.endsOn(), b.status(), b.createdAt(), b.updatedAt(), b.version());
    }
  }

  /** A budget plus where its current period stands. {@code current} is absent outside the budget's window. */
  public record StatusView(BudgetView budget, PeriodView current) {}

  public record PeriodView(LocalDate periodStart, LocalDate periodEnd, long limitPaise, long spentPaise,
      long remainingPaise, int usedBps, boolean overLimit, long projectedSpendPaise, long projectedOverspendPaise,
      int daysElapsed, int daysInPeriod, List<Integer> thresholdsCrossed) {
    static PeriodView of(BudgetMath.Status s) {
      return new PeriodView(s.periodStart(), s.periodEnd(), s.limitPaise(), s.spentPaise(), s.remainingPaise(),
          s.usedBps(), s.overLimit(), s.projectedSpendPaise(), s.projectedOverspendPaise(), s.daysElapsed(),
          s.daysInPeriod(), s.thresholdsCrossed());
    }
  }

  private final TenantTx tx;
  private final IdempotencyEngine idempotency;
  private final BudgetStore budgets;
  private final ObjectMapper mapper;
  private final Clock clock;
  private final java.util.concurrent.atomic.AtomicBoolean failNext = new java.util.concurrent.atomic.AtomicBoolean();

  public BudgetService(TenantTx tx, IdempotencyEngine idempotency, BudgetStore budgets, ObjectMapper mapper,
      Clock clock) {
    this.tx = tx;
    this.idempotency = idempotency;
    this.budgets = budgets;
    this.mapper = mapper;
    this.clock = clock;
  }

  /** Test hook (IdempotencyContract): the next create fails after its business write. */
  public void failNextCreate() {
    failNext.set(true);
  }

  public IdempotentResponse create(Tenant tenant, UUID key, NewBudget cmd) {
    if (cmd == null) {
      throw new ValidationException(List.of(new FieldError("body", "REQUIRED", "A budget")));
    }
    List<FieldError> e = new ArrayList<>();
    String name = Inputs.text("name", cmd.name(), 80, true, e);
    Period period = period(cmd.period(), e);
    String category = Inputs.category("categoryPath", cmd.categoryPath(), e);
    long limit = Inputs.amount("limitPaise", cmd.limitPaise(), e);
    List<Integer> thresholds = thresholds(cmd.alertThresholds(), e);
    LocalDate startsOn = Inputs.date("startsOn", cmd.startsOn(), false, e);
    LocalDate endsOn = Inputs.date("endsOn", cmd.endsOn(), false, e);
    LocalDate start = startsOn != null ? startsOn : today();
    if (endsOn != null && endsOn.isBefore(start)) {
      e.add(new FieldError("endsOn", "OUT_OF_RANGE", "On or after startsOn"));
    }
    if (period == Period.CUSTOM && endsOn == null) {
      e.add(new FieldError("endsOn", "REQUIRED", "A custom period needs an end"));
    }
    if (!e.isEmpty()) {
      throw new ValidationException(e);
    }
    IdempotentRequest fingerprint = IdempotentRequest.of("POST", PATH, cmd, mapper);
    return tx.write(tenant, () -> idempotency.execute(tenant, key, fingerprint, () -> {
      Instant now = now();
      BudgetRow b = new BudgetRow(Ids.newId(), tenant.accountId(), name, period.stored(), category, limit,
          Boolean.TRUE.equals(cmd.rollover()), thresholds, start, endsOn, "active", now, now, 0);
      budgets.insert(b);
      if (failNext.getAndSet(false)) {
        throw new IllegalStateException("injected failure after the business write");
      }
      return new ApiResponse(201, Map.of("Location", PATH + "/" + b.id(), "ETag", "\"0\""), BudgetView.of(b));
    }));
  }

  public Page<BudgetView> list(Tenant tenant, PageRequest page) {
    return tx.read(tenant, () -> budgets.list(page)).map(BudgetView::of);
  }

  /** {@code GET /v1/budgets/{id}} — the plan calls this one out: the old repo had no read-one. */
  public BudgetView get(Tenant tenant, UUID id) {
    return tx.read(tenant, () -> budgets.find(id).map(BudgetView::of).orElseThrow(notFound()));
  }

  /** Every active budget with its current period. */
  public List<StatusView> status(Tenant tenant) {
    LocalDate today = today();
    return tx.read(tenant, () -> {
      List<StatusView> out = new ArrayList<>();
      for (BudgetRow b : budgets.active()) {
        out.add(new StatusView(BudgetView.of(b), currentPeriod(b, today).orElse(null)));
      }
      return List.copyOf(out);
    });
  }

  /** One budget's periods, newest first; the current period is included once it has spend. */
  public Page<PeriodView> history(Tenant tenant, UUID id, PageRequest page) {
    return tx.read(tenant, () -> {
      BudgetRow b = budgets.find(id).orElseThrow(notFound());
      return budgets.history(page, id).map(s -> period(b, s.periodStart(), s.spentPaise(), today()));
    });
  }

  /** Merge patch of name, limit, rollover, thresholds, endsOn and status (active ↔ paused, or ended). */
  public BudgetView patch(Tenant tenant, UUID id, long expectedVersion, JsonNode patch) {
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
      BudgetRow b = budgets.lock(id).orElseThrow(notFound());
      if (b.version() != expectedVersion) {
        throw new VersionConflictException("Budget", expectedVersion);
      }
      if ("ended".equals(b.status())) {
        throw new DexterException(PlanningErrors.BUDGET_NOT_ACTIVE, "An ended budget is read-only");
      }
      List<FieldError> e = new ArrayList<>();
      String name = patch.has("name") ? Inputs.text("name", text(patch.get("name")), 80, true, e) : b.name();
      long limit = patch.has("limitPaise")
          ? Inputs.amount("limitPaise", longOrNull(patch.get("limitPaise")), e) : b.limitPaise();
      boolean rollover = patch.has("rollover") ? patch.get("rollover").asBoolean() : b.rollover();
      List<Integer> thresholds = patch.has("alertThresholds")
          ? thresholds(intList(patch.get("alertThresholds"), e), e) : b.alertThresholds();
      LocalDate endsOn = b.endsOn();
      if (patch.has("endsOn")) {
        endsOn = patch.get("endsOn").isNull() ? null : Inputs.date("endsOn", text(patch.get("endsOn")), true, e);
        if (endsOn != null && endsOn.isBefore(b.startsOn())) {
          e.add(new FieldError("endsOn", "OUT_OF_RANGE", "On or after startsOn"));
        }
        if (endsOn == null && "custom".equals(b.period())) {
          e.add(new FieldError("endsOn", "REQUIRED", "A custom period needs an end"));
        }
      }
      String status = b.status();
      if (patch.has("status")) {
        String s = text(patch.get("status"));
        if (!Set.of("active", "paused", "ended").contains(s)) {
          e.add(new FieldError("status", "INVALID", "active, paused or ended"));
        } else {
          status = s;
        }
      }
      if (!e.isEmpty()) {
        throw new ValidationException(e);
      }
      BudgetRow updated = new BudgetRow(b.id(), b.accountId(), name, b.period(), b.categoryPath(), limit, rollover,
          thresholds, b.startsOn(), endsOn, status, b.createdAt(), now(), b.version() + 1);
      if (same(b, updated)) {
        return BudgetView.of(b);
      }
      budgets.update(updated);
      return BudgetView.of(updated);
    });
  }

  /** Ends a budget; ending an ended budget changes nothing. Its periods and alerts stay for the history. */
  public void end(Tenant tenant, UUID id) {
    tx.writeVoid(tenant, () -> {
      BudgetRow b = budgets.lock(id).orElseThrow(notFound());
      if ("ended".equals(b.status())) {
        return;
      }
      budgets.update(new BudgetRow(b.id(), b.accountId(), b.name(), b.period(), b.categoryPath(), b.limitPaise(),
          b.rollover(), b.alertThresholds(), b.startsOn(), b.endsOn(), "ended", b.createdAt(), now(),
          b.version() + 1));
    });
  }

  // ---- shared with SpendService

  /** The period of {@code b} that {@code day} falls in, or empty outside the budget's window. */
  public static Optional<LocalDate> periodStart(BudgetRow b, LocalDate day) {
    return BudgetPeriods.periodStart(Period.of(b.period()), b.startsOn(), b.endsOn(), day);
  }

  public static LocalDate periodEnd(BudgetRow b, LocalDate periodStart) {
    return BudgetPeriods.periodEnd(Period.of(b.period()), b.startsOn(), b.endsOn(), periodStart);
  }

  public LocalDate today() {
    return LocalDate.ofInstant(clock.instant(), ZONE);
  }

  private Optional<PeriodView> currentPeriod(BudgetRow b, LocalDate today) {
    return periodStart(b, today).map(start -> {
      long spent = budgets.spend(b.id(), start).map(SpendRow::spentPaise).orElse(0L);
      return period(b, start, spent, today);
    });
  }

  private PeriodView period(BudgetRow b, LocalDate start, long spent, LocalDate today) {
    return PeriodView.of(BudgetMath.status(b.limitPaise(), spent, start, periodEnd(b, start), today,
        b.alertThresholds()));
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }

  private static java.util.function.Supplier<NotFoundException> notFound() {
    return () -> new NotFoundException("Budget");
  }

  private static boolean same(BudgetRow a, BudgetRow b) {
    return a.name().equals(b.name()) && a.limitPaise() == b.limitPaise() && a.rollover() == b.rollover()
        && a.alertThresholds().equals(b.alertThresholds()) && java.util.Objects.equals(a.endsOn(), b.endsOn())
        && a.status().equals(b.status());
  }

  private static Period period(String v, List<FieldError> e) {
    if (v == null || v.isBlank()) {
      e.add(new FieldError("period", "REQUIRED", "weekly, monthly or custom"));
      return Period.MONTHLY;
    }
    try {
      return Period.of(v.strip());
    } catch (IllegalArgumentException ex) {
      e.add(new FieldError("period", "INVALID", "weekly, monthly or custom"));
      return Period.MONTHLY;
    }
  }

  /** Stored sorted and distinct, so a consumer and the UI see one order (MASTER_PLAN P13 default 50/80/100). */
  private static List<Integer> thresholds(List<Integer> given, List<FieldError> e) {
    if (given == null || given.isEmpty()) {
      return List.of(50, 80, 100);
    }
    if (given.size() > 5) {
      e.add(new FieldError("alertThresholds", "TOO_MANY", "At most 5"));
      return List.of(50, 80, 100);
    }
    for (Integer t : given) {
      if (t == null || t < 1 || t > 500) {
        e.add(new FieldError("alertThresholds", "OUT_OF_RANGE", "Percentages from 1 to 500"));
        return List.of(50, 80, 100);
      }
    }
    return given.stream().sorted().distinct().toList();
  }

  private static List<Integer> intList(JsonNode node, List<FieldError> e) {
    if (node == null || !node.isArray()) {
      e.add(new FieldError("alertThresholds", "INVALID", "An array of percentages"));
      return List.of();
    }
    List<Integer> out = new ArrayList<>();
    for (JsonNode n : node) {
      if (!n.isIntegralNumber()) {
        e.add(new FieldError("alertThresholds", "INVALID", "An array of percentages"));
        return List.of();
      }
      out.add(n.asInt());
    }
    return out;
  }

  private static String text(JsonNode n) {
    return n == null || n.isNull() ? null : n.isString() ? n.asString() : n.toString();
  }

  private static Long longOrNull(JsonNode n) {
    return n == null || !n.isIntegralNumber() ? null : n.asLong();
  }
}
