package in.dexter.ledger.app;

import in.dexter.core.error.DexterException;
import in.dexter.core.error.FieldError;
import in.dexter.core.error.NotFoundException;
import in.dexter.core.error.ValidationException;
import in.dexter.core.id.Ids;
import in.dexter.core.tenant.Tenant;
import in.dexter.db.tenant.TenantTx;
import in.dexter.idempotency.ApiResponse;
import in.dexter.idempotency.IdempotencyEngine;
import in.dexter.idempotency.IdempotentRequest;
import in.dexter.idempotency.IdempotentResponse;
import in.dexter.ledger.app.RuleStore.Rule;
import in.dexter.ledger.domain.CategoryPaths;
import in.dexter.ledger.domain.LedgerErrors;
import in.dexter.ledger.domain.MerchantText;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;

/**
 * Categorisation rules (MASTER_PLAN P08): "merchant text contains X" or "merchant is M" → category C, evaluated in
 * priority order (lower first) by the resolver for new expenses, bulk items and imports. At most
 * {@value #MAX_RULES} per account.
 */
public final class RuleService {
  public static final String PATH = "/v1/rules";
  public static final int MAX_RULES = 200;

  /** POST /v1/rules body. {@code matchType}: {@code contains} | {@code merchant_id}. */
  public record NewRule(String matchType, String pattern, String categoryPath, Integer priority) {}

  public record RuleView(UUID id, String matchType, String pattern, String categoryPath, int priority,
      String createdAt) {
    static RuleView of(Rule r) {
      return new RuleView(r.id(), r.matchType(), r.pattern(), r.categoryPath(), r.priority(),
          r.createdAt() == null ? null : r.createdAt().toString());
    }
  }

  private final TenantTx tx;
  private final IdempotencyEngine idempotency;
  private final RuleStore rules;
  private final CategoryStore categories;
  private final ObjectMapper mapper;

  public RuleService(TenantTx tx, IdempotencyEngine idempotency, RuleStore rules, CategoryStore categories,
      ObjectMapper mapper) {
    this.tx = tx;
    this.idempotency = idempotency;
    this.rules = rules;
    this.categories = categories;
    this.mapper = mapper;
  }

  public List<RuleView> list(Tenant tenant) {
    return tx.read(tenant, () -> rules.list().stream().map(RuleView::of).toList());
  }

  /** Idempotency-Key optional. */
  public IdempotentResponse create(Tenant tenant, UUID key, NewRule cmd) {
    List<FieldError> errors = new ArrayList<>();
    String type = cmd.matchType();
    String pattern = null;
    if ("contains".equals(type)) {
      pattern = MerchantText.basic(cmd.pattern());
      if (pattern.length() < 2 || pattern.length() > 120) {
        errors.add(new FieldError("pattern", "INVALID", "2-120 letters/digits of merchant text"));
      }
    } else if ("merchant_id".equals(type)) {
      try {
        pattern = UUID.fromString(String.valueOf(cmd.pattern())).toString();
      } catch (IllegalArgumentException e) {
        errors.add(new FieldError("pattern", "INVALID", "A merchant id (uuid)"));
      }
    } else {
      errors.add(new FieldError("matchType", type == null ? "REQUIRED" : "INVALID", "contains or merchant_id"));
    }
    if (!CategoryPaths.isValid(cmd.categoryPath())) {
      errors.add(new FieldError("categoryPath", cmd.categoryPath() == null ? "REQUIRED" : "INVALID",
          "A category path"));
    }
    int priority = cmd.priority() == null ? 100 : cmd.priority();
    if (priority < 0 || priority > 10_000) {
      errors.add(new FieldError("priority", "OUT_OF_RANGE", "0-10000 (lower runs first)"));
    }
    if (!errors.isEmpty()) {
      throw new ValidationException(errors);
    }
    String p = pattern;
    IdempotentRequest fingerprint = IdempotentRequest.of("POST", PATH, cmd, mapper);
    return tx.write(tenant, () -> idempotency.execute(tenant, key, fingerprint, () -> {
      if (categories.findVisible(cmd.categoryPath()).isEmpty()) {
        throw new ValidationException(List.of(new FieldError("categoryPath", "UNKNOWN_CATEGORY",
            "No such category (GET /v1/categories)")));
      }
      if (rules.count() >= MAX_RULES) {
        throw new DexterException(LedgerErrors.RULE_LIMIT_REACHED, "At most " + MAX_RULES + " rules");
      }
      UUID id = Ids.newId();
      rules.insert(id, tenant.accountId(), type, p, cmd.categoryPath(), priority);
      Rule created = rules.list().stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow();
      return ApiResponse.created(PATH + "/" + id, RuleView.of(created));
    }));
  }

  public void delete(Tenant tenant, UUID id) {
    boolean deleted = tx.write(tenant, () -> rules.delete(id));
    if (!deleted) {
      throw new NotFoundException("rule");
    }
  }
}
