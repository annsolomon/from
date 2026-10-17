package in.dexter.ledger;

import in.dexter.ledger.app.ExpenseService;
import in.dexter.ledger.support.LedgerIT;
import in.dexter.testkit.contract.HttpResult;
import in.dexter.testkit.contract.IdempotencyContract;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.springframework.beans.factory.annotation.Autowired;

/** MASTER_PLAN 5.5 on POST /v1/expenses (Idempotency-Key required). */
class CreateExpenseIdempotencyContractIT extends LedgerIT {
  @Autowired
  ExpenseService expenses;

  @Nested
  class Contract extends IdempotencyContract {
    @Override
    protected HttpResult mutate(UUID account, UUID key, String body) {
      Res r = key == null ? send("POST", "/v1/expenses", body, "application/json", auth(account))
          : send("POST", "/v1/expenses", body, "application/json", auth(account, "Idempotency-Key", key.toString()));
      return new HttpResult(r.status(), r.headers(), r.body());
    }

    @Override
    protected String body(int n) {
      return "{\"occurredAt\":\"2026-10-0" + (1 + n % 9) + "T10:00:00Z\",\"amountPaise\":" + (1000 + n)
          + ",\"merchant\":\"Contract\",\"paymentMethod\":\"upi\",\"categoryPath\":\"food\"}";
    }

    @Override
    protected long businessRows(UUID account) {
      return ADMIN.sql("SELECT count(1) FROM ledger.expenses WHERE account_id = :a").param("a", account)
          .query(Long.class).single();
    }

    @Override
    protected long outboxRows(UUID account) {
      return LedgerIT.outboxRows(account, "ledger.expense.created");
    }

    @Override
    protected void failNextMutation() {
      expenses.failNextCreate();
    }
  }
}
