package in.dexter.audit;

import in.dexter.audit.infra.NotificationRepository;
import in.dexter.audit.support.AuditIT;
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
 * MASTER_PLAN 4.5 on GET /v1/notifications (the in-app inbox, newest first), plus the list plan on 200,000 in-app
 * notifications of 1,000 accounts and one heavy account of 3,000 (notifications_inbox, no Sort). The account is
 * unknown when the plan is made (an InitPlan), and the SYSTEM policy's OR halves the estimate: at 60 rows per account
 * the planner rightly sorts ~30 (P18 r3); at 200 it walks the index.
 */
class NotificationsPaginationContractIT extends AuditIT {
  static final AtomicLong SEED_HOUR = new AtomicLong();
  static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");
  static final UUID HEAVY = UUID.fromString("0000000a-0000-7000-8000-0000000a111e");
  static volatile boolean bulk;

  @Autowired
  TenantTx tx;
  @Autowired
  JdbcClient jdbc;
  @Autowired
  NotificationRepository repo;

  static final String SEED = """
      INSERT INTO audit.notifications (id, account_id, channel, category, template, payload, status,
                                       source_event_id, created_at, sent_at)
      SELECT gen_random_uuid(), CAST(:a AS uuid), 'inapp', 'budget', 'budget.threshold', '{}'::jsonb, 'sent',
             gen_random_uuid(),
             COALESCE(CAST(:at AS timestamptz), CAST(:base AS timestamptz) + g * interval '1 millisecond'), now()
      FROM generate_series(1, :n) g
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
      seedFor(UUID.nameUUIDFromBytes(("bulk-notification-acct-" + i).getBytes()), 200, null,
          Instant.parse("2025-01-01T00:00:00Z").plus(i, ChronoUnit.MINUTES));
    }
    seedFor(HEAVY, 3000, null, Instant.parse("2025-06-01T00:00:00Z"));
    ADMIN.sql("ANALYZE audit.notifications").update();
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
      StringBuilder q = new StringBuilder("/v1/notifications?x=1");
      if (limit != null) {
        q.append("&limit=").append(limit);
      }
      if (cursor != null) {
        q.append("&cursor=").append(java.net.URLEncoder.encode(cursor, java.nio.charset.StandardCharsets.UTF_8));
      }
      filters.forEach((k, v) -> q.append('&').append(k).append('=').append(v));
      Res r = user("GET", q.toString(), account, null);
      return new HttpResult(r.status(), r.headers(), r.body());
    }

    @Override
    protected List<String> expectedOrder(UUID account) {
      return ADMIN.sql("SELECT id::text FROM audit.notifications WHERE account_id = :a AND channel = 'inapp'"
          + " ORDER BY created_at DESC, id DESC").param("a", account).query(String.class).list();
    }

    @Override
    protected List<Map<String, String>> twoDifferentFilters() {
      return List.of(Map.of("unread", "true"), Map.of("unread", "false"));
    }

    @Override
    protected void assertListPlan() {
      ensureBulk();
      tx.read(Tenant.user(HEAVY), () -> PlanAssert.query(jdbc, repo.inbox.firstPageSql(), Map.of("__limit", 51))
          .forbidSeqScanOn("notifications").forbidSort().requireIndex("notifications_inbox").verify());
      tx.read(Tenant.user(HEAVY), () -> PlanAssert.query(jdbc, repo.inbox.nextPageSql(),
              Map.of("__limit", 26, "__k0", Sql.ts(Instant.parse("2025-06-01T00:00:00.030Z")),
                  "__k1", UUID.fromString("ffffffff-ffff-7fff-bfff-ffffffffffff")))
          .forbidSeqScanOn("notifications").forbidSort().requireIndex("notifications_inbox").verify());
    }
  }
}
