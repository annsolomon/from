package in.dexter.portfolio;

import in.dexter.portfolio.app.CardService;
import in.dexter.portfolio.app.TransactionService;
import in.dexter.portfolio.support.PortfolioIT;
import in.dexter.testkit.contract.HttpResult;
import in.dexter.testkit.contract.IdempotencyContract;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Nested;
import org.springframework.beans.factory.annotation.Autowired;

/** MASTER_PLAN 5.5 on POST /v1/cards and POST /v1/cards/{id}/transactions (Idempotency-Key required). */
class IdempotencyContractsIT extends PortfolioIT {
  @Autowired
  TransactionService txns;
  @Autowired
  CardService cardsSvc;

  static final Map<UUID, UUID> CARDS = new ConcurrentHashMap<>();

  static UUID cardOf(UUID account) {
    return CARDS.computeIfAbsent(account, a -> {
      UUID id = UUID.randomUUID();
      ADMIN.sql("INSERT INTO portfolio.user_cards (id, account_id, card_product_id, nickname, last4, network,"
          + " statement_day) VALUES (:id, :a, :p, 'Contract', '0001', 'visa', 15)").param("id", id).param("a", a)
          .param("p", POINTS_CARD).update();
      return id;
    });
  }

  HttpResult send(String path, UUID account, UUID key, String body) {
    Res r = key == null ? send("POST", path, body, "application/json", auth(account))
        : send("POST", path, body, "application/json", auth(account, "Idempotency-Key", key.toString()));
    return new HttpResult(r.status(), r.headers(), r.body());
  }

  @Nested
  class AddCard extends IdempotencyContract {
    @Override
    protected HttpResult mutate(UUID account, UUID key, String body) {
      return send("/v1/cards", account, key, body);
    }

    @Override
    protected String body(int n) {
      return "{\"cardProductId\":\"" + POINTS_CARD + "\",\"last4\":\"" + (4000 + n) + "\",\"statementDay\":10}";
    }

    @Override
    protected long businessRows(UUID account) {
      return ADMIN.sql("SELECT count(1) FROM portfolio.user_cards WHERE account_id = :a").param("a", account)
          .query(Long.class).single();
    }

    @Override
    protected long outboxRows(UUID account) {
      return PortfolioIT.outboxRows(account, "portfolio.card.added");
    }

    @Override
    protected void failNextMutation() {
      cardsSvc.failNextCreate();
    }
  }

  @Nested
  class RecordTransaction extends IdempotencyContract {
    @Override
    protected HttpResult mutate(UUID account, UUID key, String body) {
      return send("/v1/cards/" + cardOf(account) + "/transactions", account, key, body);
    }

    @Override
    protected String body(int n) {
      return "{\"postedAt\":\"2026-10-0" + (1 + n % 9) + "T10:00:00Z\",\"amountPaise\":" + (1000 + n)
          + ",\"kind\":\"purchase\",\"description\":\"Contract\",\"categoryPath\":\"food.delivery\"}";
    }

    @Override
    protected long businessRows(UUID account) {
      return ADMIN.sql("SELECT count(1) FROM portfolio.card_transactions WHERE account_id = :a").param("a", account)
          .query(Long.class).single();
    }

    @Override
    protected long outboxRows(UUID account) {
      return PortfolioIT.outboxRows(account, "portfolio.transaction.recorded");
    }

    @Override
    protected void failNextMutation() {
      txns.failNextRecord();
    }
  }
}
