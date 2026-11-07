package in.dexter.billing;

import in.dexter.billing.infra.InvoiceRepository;
import in.dexter.billing.support.BillingIT;
import in.dexter.core.tenant.Tenant;
import in.dexter.db.plan.PlanAssert;
import in.dexter.db.sql.Sql;
import in.dexter.db.tenant.TenantTx;
import in.dexter.testkit.contract.HttpResult;
import in.dexter.testkit.contract.PaginationContract;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Nested;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * MASTER_PLAN 4.5 on GET /v1/invoices (newest first), plus the list plan on 60,000 invoices of 1,000 accounts
 * (invoices_list, no Sort). An invoice needs a payment and a subscription, so the seed writes all three.
 */
class InvoicesPaginationContractIT extends BillingIT {
  static final AtomicLong SEED_HOUR = new AtomicLong();
  static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");
  static final UUID HEAVY = UUID.fromString("0000000a-0000-7000-8000-0000000b111e");
  static volatile boolean bulk;

  @Autowired
  TenantTx tx;
  @Autowired
  JdbcClient jdbc;

  /** {@code n} paid invoices for {@code account}, each with its own payment, under one closed subscription. */
  static final String SEED = """
      WITH s AS (
        INSERT INTO billing.subscriptions (id, account_id, plan_id, status, current_period_start, current_period_end,
                                           provider, billing_state)
        VALUES (gen_random_uuid(), CAST(:a AS uuid), '0192f1e2-0000-7000-8000-00000000b002', 'expired',
                now() - interval '1 year', now() - interval '11 months', 'mock', 'KA')
        RETURNING id, account_id
      ), p AS (
        INSERT INTO billing.payments (id, account_id, subscription_id, idem_key, amount_paise, status, provider,
                                      payment_method, decided_at)
        SELECT gen_random_uuid(), s.account_id, s.id, gen_random_uuid(), 23482, 'succeeded', 'mock',
               'pm_mock_success', now()
        FROM s, generate_series(1, :n) g
        RETURNING id, account_id, subscription_id
      ), numbered AS (SELECT p.*, row_number() OVER () AS g FROM p)
      INSERT INTO billing.invoices (id, account_id, subscription_id, payment_id, number, issued_at, period_start,
                                    period_end, supplier_state, place_of_supply, tax_regime, subtotal_paise,
                                    tax_paise, total_paise)
      SELECT gen_random_uuid(), account_id, subscription_id, id,
             'DX/2025-26/' || lpad(nextval('billing.invoice_number_seq')::text, 6, '0'),
             COALESCE(CAST(:at AS timestamptz), CAST(:base AS timestamptz) + g * interval '1 millisecond'),
             now() - interval '1 year', now() - interval '11 months', 'KA', 'KA', 'intra', 19900, 3582, 23482
      FROM numbered
      """;

  static void seedFor(UUID account, int n, Instant at, Instant base) {
    ADMIN.sql(SEED).param("a", account).param("n", n).param("at", at == null ? null : Sql.ts(at))
        .param("base", Sql.ts(base)).update();
  }

  static synchronized void ensureBulk() {
    if (bulk) {
      return;
    }
    for (int i = 1; i <= 1000; i++) {
      seedFor(UUID.nameUUIDFromBytes(("bulk-invoice-acct-" + i).getBytes()), 60, null,
          Instant.parse("2025-01-01T00:00:00Z").plus(i, ChronoUnit.MINUTES));
    }
    seedFor(HEAVY, 60, null, Instant.parse("2025-06-01T00:00:00Z"));
    ADMIN.sql("ANALYZE billing.invoices").update();
    bulk = true;
  }

  @Nested
  class Contract extends PaginationContract {
    @Override
    protected void seed(UUID account, int n, Instant at) {
      seedFor(account, n, at, BASE.plus(SEED_HOUR.incrementAndGet(), ChronoUnit.HOURS));
    }

    @Override
    protected HttpResult list(UUID account, Integer limit, String cursor, Map<String, String> filters) {
      StringBuilder q = new StringBuilder("/v1/invoices?x=1");
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
      return ADMIN.sql("SELECT id::text FROM billing.invoices WHERE account_id = :a"
          + " ORDER BY issued_at DESC, id DESC").param("a", account).query(String.class).list();
    }

    @Override
    protected List<Map<String, String>> twoDifferentFilters() {
      return null;
    }

    @Override
    protected void assertListPlan() {
      ensureBulk();
      tx.read(Tenant.user(HEAVY), () -> PlanAssert.query(jdbc, InvoiceRepository.LIST.firstPageSql(),
              Map.of("__limit", 51))
          .forbidSeqScanOn("invoices").forbidSort().requireIndex("invoices_list").verify());
      tx.read(Tenant.user(HEAVY), () -> PlanAssert.query(jdbc, InvoiceRepository.LIST.nextPageSql(),
              Map.of("__limit", 26, "__k0", Sql.ts(Instant.parse("2025-06-01T00:00:00.030Z")),
                  "__k1", UUID.fromString("ffffffff-ffff-7fff-bfff-ffffffffffff")))
          .forbidSeqScanOn("invoices").forbidSort().requireIndex("invoices_list").verify());
    }
  }
}
