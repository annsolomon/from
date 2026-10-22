package in.dexter.portfolio;

import in.dexter.core.tenant.Tenant;
import in.dexter.db.plan.PlanAssert;
import in.dexter.db.sql.Sql;
import in.dexter.db.tenant.TenantTx;
import in.dexter.portfolio.infra.TransactionRepository;
import in.dexter.portfolio.support.PortfolioIT;
import in.dexter.testkit.contract.HttpResult;
import in.dexter.testkit.contract.PaginationContract;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Nested;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * MASTER_PLAN 4.5 on GET /v1/cards/{id}/transactions (newest first), plus the list plan on 300,000 transactions of
 * 1,000 accounts (card_transactions_list, no Sort).
 */
class TransactionsPaginationContractIT extends PortfolioIT {
  static final AtomicLong SEED_HOUR = new AtomicLong();
  static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");
  static final Map<UUID, UUID> CARDS = new ConcurrentHashMap<>();
  static final UUID HEAVY = UUID.fromString("0000000a-0000-7000-8000-00000000beef");
  static final UUID HEAVY_CARD = UUID.fromString("0000000c-0000-7000-8000-00000000beef");
  static volatile boolean bulk;

  @Autowired
  TenantTx tx;
  @Autowired
  JdbcClient jdbc;

  static UUID cardOf(UUID account) {
    return CARDS.computeIfAbsent(account, a -> {
      UUID id = UUID.randomUUID();
      ADMIN.sql("INSERT INTO portfolio.user_cards (id, account_id, card_product_id, nickname, last4, network,"
          + " statement_day) VALUES (:id, :a, :p, 'Contract', '0001', 'visa', 15)").param("id", id).param("a", a)
          .param("p", POINTS_CARD).update();
      return id;
    });
  }

  static synchronized void ensureBulk() {
    if (bulk) {
      return;
    }
    ADMIN.sql("INSERT INTO portfolio.user_cards (id, account_id, card_product_id, nickname, last4, network,"
        + " statement_day) SELECT md5('bulk-card' || g)::uuid, md5('bulk-acct' || g)::uuid, :p, 'Bulk', '0001',"
        + " 'visa', 15 FROM generate_series(1, 1000) g ON CONFLICT DO NOTHING").param("p", POINTS_CARD).update();
    ADMIN.sql("INSERT INTO portfolio.user_cards (id, account_id, card_product_id, nickname, last4, network,"
        + " statement_day) VALUES (:id, :a, :p, 'Heavy', '0002', 'visa', 15) ON CONFLICT DO NOTHING")
        .param("id", HEAVY_CARD).param("a", HEAVY).param("p", POINTS_CARD).update();
    ADMIN.sql("""
        INSERT INTO portfolio.card_transactions (id, account_id, user_card_id, posted_at, amount_paise, direction,
                                                 description)
        SELECT gen_random_uuid(), md5('bulk-acct' || (g % 1000 + 1))::uuid, md5('bulk-card' || (g % 1000 + 1))::uuid,
               TIMESTAMPTZ '2025-01-01' + g * interval '97 seconds', 100 + g % 5000, 'debit', 'BULK'
        FROM generate_series(1, 300000) g
        """).update();
    ADMIN.sql("""
        INSERT INTO portfolio.card_transactions (id, account_id, user_card_id, posted_at, amount_paise, direction,
                                                 description)
        SELECT gen_random_uuid(), CAST(:a AS uuid), CAST(:c AS uuid), TIMESTAMPTZ '2025-01-01' + g * interval '1 hour',
               100 + g, 'debit', 'HEAVY'
        FROM generate_series(1, 5000) g
        """).param("a", HEAVY).param("c", HEAVY_CARD).update();
    ADMIN.sql("ANALYZE portfolio.card_transactions").update();
    ADMIN.sql("ANALYZE portfolio.user_cards").update();
    bulk = true;
  }

  @Nested
  class Contract extends PaginationContract {
    @Override
    protected void seed(UUID account, int n, Instant at) {
      Instant base = BASE.plus(SEED_HOUR.incrementAndGet(), ChronoUnit.HOURS);
      ADMIN.sql("""
          INSERT INTO portfolio.card_transactions (id, account_id, user_card_id, posted_at, amount_paise, direction,
                                                   description)
          SELECT gen_random_uuid(), :a, :c,
                 COALESCE(CAST(:at AS timestamptz), CAST(:base AS timestamptz) + g * interval '1 millisecond'),
                 100 + g, 'debit', 'Contract'
          FROM generate_series(1, :n) g
          """).param("a", account).param("c", cardOf(account)).param("at", at == null ? null : Sql.ts(at))
          .param("base", Sql.ts(base)).param("n", n).update();
    }

    @Override
    protected HttpResult list(UUID account, Integer limit, String cursor, Map<String, String> filters) {
      StringBuilder q = new StringBuilder("/v1/cards/" + cardOf(account) + "/transactions?x=1");
      if (limit != null) {
        q.append("&limit=").append(limit);
      }
      if (cursor != null) {
        q.append("&cursor=").append(java.net.URLEncoder.encode(cursor, java.nio.charset.StandardCharsets.UTF_8));
      }
      Res r = get(q.toString(), account);
      return new HttpResult(r.status(), r.headers(), r.body());
    }

    @Override
    protected List<String> expectedOrder(UUID account) {
      return ADMIN.sql("SELECT id::text FROM portfolio.card_transactions WHERE account_id = :a"
          + " ORDER BY posted_at DESC, id DESC").param("a", account).query(String.class).list();
    }

    @Override
    protected List<Map<String, String>> twoDifferentFilters() {
      return null;
    }

    @Override
    protected void assertListPlan() {
      ensureBulk();
      tx.read(Tenant.user(HEAVY), () -> PlanAssert.query(jdbc, TransactionRepository.LIST.firstPageSql(),
              Map.of("__limit", 51, "card", HEAVY_CARD))
          .forbidSeqScanOn("card_transactions").forbidSort().requireIndex("card_transactions_list").verify());
      tx.read(Tenant.user(HEAVY), () -> PlanAssert.query(jdbc, TransactionRepository.LIST.nextPageSql(),
              Map.of("__limit", 26, "card", HEAVY_CARD, "__k0", Sql.ts(Instant.parse("2025-04-01T00:00:00Z")),
                  "__k1", UUID.fromString("ffffffff-ffff-7fff-bfff-ffffffffffff")))
          .forbidSeqScanOn("card_transactions").forbidSort().requireIndex("card_transactions_list").verify());
    }
  }
}
