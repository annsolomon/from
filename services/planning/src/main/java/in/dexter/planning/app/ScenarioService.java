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
import in.dexter.planning.app.LiabilityStore.LiabilityRow;
import in.dexter.planning.app.ScenarioStore.ScenarioRow;
import in.dexter.planning.domain.PlanningErrors;
import in.dexter.planning.domain.ScenarioMath;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * What-if scenarios (MASTER_PLAN P13). Saving one copies the account's active budgets and liabilities into
 * {@code base_snapshot} — copy-on-read, never a live view — so evaluating the same scenario next month still
 * answers the question it was saved to ask. Evaluating applies the stored adjustments to that copy.
 *
 * <p>This layer owns the JSON: the domain ({@link ScenarioMath}) sees records only.
 */
public final class ScenarioService {
  public static final String PATH = "/v1/scenarios";

  static final Set<String> PATCHABLE = Set.of("name", "adjustments");
  static final int MAX_ADJUSTMENTS = 20;

  /** One adjustment as the stored JSON carries it: a {@code kind} plus the fields that kind needs. */
  public record AdjustmentDto(String kind, Long amountPaise, String budgetId, Long limitPaise, String liabilityId,
      String lender, Long principalPaise, Integer aprBps, Integer tenureMonths) {}

  public record BudgetDto(String id, String name, long limitPaise) {}

  public record LiabilityDto(String id, String lender, long emiPaise) {}

  public record SnapshotDto(long monthlyIncomePaise, List<BudgetDto> budgets, List<LiabilityDto> liabilities) {}

  /** POST /v1/scenarios body (also the Idempotency-Key fingerprint). */
  public record NewScenario(String name, Long monthlyIncomePaise, List<AdjustmentDto> adjustments) {}

  public record ScenarioView(UUID id, String name, SnapshotDto baseSnapshot, List<AdjustmentDto> adjustments,
      Instant createdAt, Instant updatedAt, int version) {}

  public record LineView(String kind, String id, String label, long monthlyPaise, boolean added) {}

  public record EvaluationView(UUID scenarioId, String name, long monthlyIncomePaise, long monthlyOutgoBeforePaise,
      long monthlyOutgoAfterPaise, long deltaPaise, long surplusBeforePaise, long surplusAfterPaise,
      boolean feasible, List<LineView> lines) {}

  private final TenantTx tx;
  private final IdempotencyEngine idempotency;
  private final ScenarioStore scenarios;
  private final BudgetStore budgets;
  private final LiabilityStore liabilities;
  private final ObjectMapper mapper;
  private final Clock clock;

  public ScenarioService(TenantTx tx, IdempotencyEngine idempotency, ScenarioStore scenarios, BudgetStore budgets,
      LiabilityStore liabilities, ObjectMapper mapper, Clock clock) {
    this.tx = tx;
    this.idempotency = idempotency;
    this.scenarios = scenarios;
    this.budgets = budgets;
    this.liabilities = liabilities;
    this.mapper = mapper;
    this.clock = clock;
  }

  public IdempotentResponse create(Tenant tenant, UUID key, NewScenario cmd) {
    if (cmd == null) {
      throw new ValidationException(List.of(new FieldError("body", "REQUIRED", "A scenario")));
    }
    List<FieldError> e = new ArrayList<>();
    String name = Inputs.text("name", cmd.name(), 80, true, e);
    long income = cmd.monthlyIncomePaise() == null ? 0L : cmd.monthlyIncomePaise();
    if (income < 0 || income > Inputs.MAX_AMOUNT) {
      e.add(new FieldError("monthlyIncomePaise", "OUT_OF_RANGE", "0 to " + Inputs.MAX_AMOUNT + " paise"));
    }
    List<AdjustmentDto> adjustments = adjustments(cmd.adjustments(), e);
    if (!e.isEmpty()) {
      throw new ValidationException(e);
    }
    IdempotentRequest fingerprint = IdempotentRequest.of("POST", PATH, cmd, mapper);
    return tx.write(tenant, () -> idempotency.execute(tenant, key, fingerprint, () -> {
      SnapshotDto snapshot = snapshot(income);
      // the adjustments must make sense against the snapshot that was just taken
      evaluate(snapshot, adjustments);
      Instant now = now();
      ScenarioRow s = new ScenarioRow(Ids.newId(), tenant.accountId(), name, mapper.writeValueAsString(snapshot),
          mapper.writeValueAsString(adjustments), now, now, 0);
      scenarios.insert(s);
      return new ApiResponse(201, Map.of("Location", PATH + "/" + s.id(), "ETag", "\"0\""), view(s));
    }));
  }

  public Page<ScenarioView> list(Tenant tenant, PageRequest page) {
    return tx.read(tenant, () -> scenarios.list(page)).map(this::view);
  }

  public ScenarioView get(Tenant tenant, UUID id) {
    return tx.read(tenant, () -> scenarios.find(id).map(this::view).orElseThrow(notFound()));
  }

  /** Merge patch of {@code name} and {@code adjustments}; the snapshot is never re-taken. */
  public ScenarioView patch(Tenant tenant, UUID id, long expectedVersion, JsonNode patch) {
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
      ScenarioRow s = scenarios.lock(id).orElseThrow(notFound());
      if (s.version() != expectedVersion) {
        throw new VersionConflictException("Scenario", expectedVersion);
      }
      List<FieldError> e = new ArrayList<>();
      String name = patch.has("name") ? Inputs.text("name", text(patch.get("name")), 80, true, e) : s.name();
      String adjustmentsJson = s.adjustments();
      if (patch.has("adjustments")) {
        List<AdjustmentDto> given = adjustments(
            mapper.convertValue(patch.get("adjustments"), ADJUSTMENT_LIST), e);
        if (e.isEmpty()) {
          evaluate(parseSnapshot(s), given);
          adjustmentsJson = mapper.writeValueAsString(given);
        }
      }
      if (!e.isEmpty()) {
        throw new ValidationException(e);
      }
      if (name.equals(s.name()) && adjustmentsJson.equals(s.adjustments())) {
        return view(s);
      }
      ScenarioRow updated = new ScenarioRow(s.id(), s.accountId(), name, s.baseSnapshot(), adjustmentsJson,
          s.createdAt(), now(), s.version() + 1);
      scenarios.update(updated);
      return view(updated);
    });
  }

  /** {@code POST /v1/scenarios/{id}:evaluate} — pure, so it stores nothing and needs no key. */
  public EvaluationView evaluate(Tenant tenant, UUID id) {
    return tx.read(tenant, () -> {
      ScenarioRow s = scenarios.find(id).orElseThrow(notFound());
      ScenarioMath.Result r = evaluate(parseSnapshot(s), parseAdjustments(s));
      List<LineView> lines = r.lines().stream()
          .map(l -> new LineView(l.kind(), l.id(), l.label(), l.monthlyPaise(), l.added())).toList();
      return new EvaluationView(s.id(), s.name(), r.monthlyIncomePaise(), r.monthlyOutgoBeforePaise(),
          r.monthlyOutgoAfterPaise(), r.deltaPaise(), r.surplusBeforePaise(), r.surplusAfterPaise(), r.feasible(),
          lines);
    });
  }

  // ---- JSON ↔ domain

  static final tools.jackson.core.type.TypeReference<List<AdjustmentDto>> ADJUSTMENT_LIST =
      new tools.jackson.core.type.TypeReference<>() {};

  /** The account's active budgets and liabilities, copied in. */
  private SnapshotDto snapshot(long monthlyIncomePaise) {
    List<BudgetDto> b = budgets.active().stream()
        .map(r -> new BudgetDto(r.id().toString(), r.name(), r.limitPaise())).toList();
    List<LiabilityDto> l = liabilities.active().stream()
        .map(r -> new LiabilityDto(r.id().toString(), r.lender(), r.emiPaise())).toList();
    return new SnapshotDto(monthlyIncomePaise, b, l);
  }

  private ScenarioMath.Result evaluate(SnapshotDto snapshot, List<AdjustmentDto> adjustments) {
    ScenarioMath.Snapshot base = new ScenarioMath.Snapshot(snapshot.monthlyIncomePaise(),
        snapshot.budgets().stream().map(x -> new ScenarioMath.BudgetLine(x.id(), x.name(), x.limitPaise())).toList(),
        snapshot.liabilities().stream().map(x -> new ScenarioMath.LiabilityLine(x.id(), x.lender(), x.emiPaise()))
            .toList());
    try {
      return ScenarioMath.evaluate(base, adjustments.stream().map(ScenarioService::toDomain).toList());
    } catch (IllegalArgumentException ex) {
      throw new DexterException(PlanningErrors.SCENARIO_ADJUSTMENT_INVALID, ex.getMessage());
    }
  }

  private static ScenarioMath.Adjustment toDomain(AdjustmentDto a) {
    return switch (a.kind()) {
      case "setMonthlyIncome" -> new ScenarioMath.Adjustment.SetMonthlyIncome(orZero(a.amountPaise()));
      case "setBudgetLimit" -> new ScenarioMath.Adjustment.SetBudgetLimit(a.budgetId(), orZero(a.limitPaise()));
      case "removeLiability" -> new ScenarioMath.Adjustment.RemoveLiability(a.liabilityId());
      case "addLiability" -> new ScenarioMath.Adjustment.AddLiability(a.lender(), orZero(a.principalPaise()),
          a.aprBps() == null ? 0 : a.aprBps(), a.tenureMonths() == null ? 0 : a.tenureMonths());
      default -> throw new IllegalArgumentException("unknown adjustment kind: " + a.kind());
    };
  }

  private static long orZero(Long v) {
    return v == null ? 0L : v;
  }

  private List<AdjustmentDto> adjustments(List<AdjustmentDto> given, List<FieldError> e) {
    if (given == null || given.isEmpty()) {
      return List.of();
    }
    if (given.size() > MAX_ADJUSTMENTS) {
      e.add(new FieldError("adjustments", "TOO_MANY", "At most " + MAX_ADJUSTMENTS));
      return List.of();
    }
    for (int i = 0; i < given.size(); i++) {
      AdjustmentDto a = given.get(i);
      if (a == null || a.kind() == null || a.kind().isBlank()) {
        e.add(new FieldError("adjustments[" + i + "].kind", "REQUIRED",
            "setMonthlyIncome, setBudgetLimit, addLiability or removeLiability"));
      }
    }
    return e.isEmpty() ? List.copyOf(given) : List.of();
  }

  private SnapshotDto parseSnapshot(ScenarioRow s) {
    return mapper.readValue(s.baseSnapshot(), SnapshotDto.class);
  }

  private List<AdjustmentDto> parseAdjustments(ScenarioRow s) {
    return mapper.readValue(s.adjustments(), ADJUSTMENT_LIST);
  }

  private ScenarioView view(ScenarioRow s) {
    return new ScenarioView(s.id(), s.name(), parseSnapshot(s), parseAdjustments(s), s.createdAt(), s.updatedAt(),
        s.version());
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }

  private static java.util.function.Supplier<NotFoundException> notFound() {
    return () -> new NotFoundException("Scenario");
  }

  private static String text(JsonNode n) {
    return n == null || n.isNull() ? null : n.isString() ? n.asString() : n.toString();
  }
}
