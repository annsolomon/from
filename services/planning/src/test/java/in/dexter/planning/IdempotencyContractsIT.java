package in.dexter.planning;

import in.dexter.planning.app.BudgetService;
import in.dexter.planning.app.LiabilityService;
import in.dexter.planning.support.PlanningIT;
import in.dexter.testkit.contract.HttpResult;
import in.dexter.testkit.contract.IdempotencyContract;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * MASTER_PLAN 5.5 on POST /v1/budgets and POST /v1/liabilities (both need an Idempotency-Key). Creating a budget
 * writes no event; creating a liability does, so the two cases cover both shapes of the contract.
 */
class IdempotencyContractsIT extends PlanningIT {
  @Autowired
  BudgetService budgetsSvc;
  @Autowired
  LiabilityService liabilitiesSvc;

  HttpResult mutation(String path, UUID account, UUID key, String body) {
    Res r = key == null ? send("POST", path, body, "application/json", auth(account))
        : send("POST", path, body, "application/json", auth(account, "Idempotency-Key", key.toString()));
    return new HttpResult(r.status(), r.headers(), r.body());
  }

  @Nested
  class CreateBudget extends IdempotencyContract {
    @Override
    protected HttpResult mutate(UUID account, UUID key, String body) {
      return mutation("/v1/budgets", account, key, body);
    }

    @Override
    protected String body(int n) {
      return "{\"name\":\"Contract " + n + "\",\"period\":\"monthly\",\"limitPaise\":" + (100_000 + n)
          + ",\"startsOn\":\"2026-01-01\"}";
    }

    @Override
    protected long businessRows(UUID account) {
      return ADMIN.sql("SELECT count(1) FROM planning.budgets WHERE account_id = :a").param("a", account)
          .query(Long.class).single();
    }

    @Override
    protected long outboxRows(UUID account) {
      return 0;
    }

    @Override
    protected boolean writesEvent() {
      return false;
    }

    @Override
    protected void failNextMutation() {
      budgetsSvc.failNextCreate();
    }
  }

  @Nested
  class CreateLiability extends IdempotencyContract {
    @Override
    protected HttpResult mutate(UUID account, UUID key, String body) {
      return mutation("/v1/liabilities", account, key, body);
    }

    @Override
    protected String body(int n) {
      return "{\"kind\":\"loan\",\"lender\":\"Contract " + n + "\",\"principalPaise\":" + (1_000_000 + n)
          + ",\"aprBps\":1200,\"tenureMonths\":12,\"startDate\":\"2026-02-01\"}";
    }

    @Override
    protected long businessRows(UUID account) {
      return ADMIN.sql("SELECT count(1) FROM planning.liabilities WHERE account_id = :a").param("a", account)
          .query(Long.class).single();
    }

    @Override
    protected long outboxRows(UUID account) {
      return PlanningIT.outboxRows(account, "planning.liability.changed");
    }

    @Override
    protected void failNextMutation() {
      liabilitiesSvc.failNextCreate();
    }
  }
}
