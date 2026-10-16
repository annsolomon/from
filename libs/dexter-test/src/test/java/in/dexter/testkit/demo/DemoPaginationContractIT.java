package in.dexter.testkit.demo;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import in.dexter.core.tenant.Tenant;
import in.dexter.db.plan.PlanAssert;
import in.dexter.db.sql.Sql;
import in.dexter.testkit.contract.HttpResult;
import in.dexter.testkit.contract.PaginationContract;
import in.dexter.testkit.demo.config.CacheScopes;
import in.dexter.testkit.demo.config.DemoApp;
import in.dexter.testkit.demo.infra.NoteRepository;
import in.dexter.testkit.support.Mvc;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** MASTER_PLAN 4.5 on GET /demo/notes. */
class DemoPaginationContractIT extends PaginationContract {
  /** Every seed call is strictly newer than the previous one (1 hour apart), so "inserted mid-walk" rows are newer. */
  static final AtomicLong SEED_HOUR = new AtomicLong();
  static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");
  final DemoApp app = DemoApp.get();

  @Override
  protected void seed(UUID account, int n, Instant at) {
    Instant base = BASE.plus(SEED_HOUR.incrementAndGet(), ChronoUnit.HOURS);
    app.admin.sql("""
        INSERT INTO demo.notes (account_id, id, title, kind, amount_paise, created_at)
        SELECT :a, gen_random_uuid(), 'n' || g, CASE WHEN g % 2 = 0 THEN 'note' ELSE 'memo' END, g,
               COALESCE(CAST(:at AS timestamptz), CAST(:base AS timestamptz) + g * interval '1 millisecond')
        FROM generate_series(1, :n) g
        """).param("a", account).param("at", Sql.ts(at)).param("base", Sql.ts(base)).param("n", n).update();
    // the seed bypasses the use-cases, so it must invalidate the cached pages itself (as Path A would)
    app.cache.invalidator().bump(account, List.of(CacheScopes.NOTES));
  }

  @Override
  protected HttpResult list(UUID account, Integer limit, String cursor, Map<String, String> filters) throws Exception {
    MockHttpServletRequestBuilder req = get("/demo/notes").header("Authorization", app.bearer(account));
    if (limit != null) {
      req.param("limit", Integer.toString(limit));
    }
    if (cursor != null) {
      req.param("cursor", cursor);
    }
    filters.forEach(req::param);
    return Mvc.call(app.mvc, req);
  }

  @Override
  protected List<String> expectedOrder(UUID account) {
    return app.admin.sql("SELECT id::text FROM demo.notes WHERE account_id = :a ORDER BY created_at DESC, id DESC")
        .param("a", account).query(String.class).list();
  }

  @Override
  protected List<Map<String, String>> twoDifferentFilters() {
    return List.of(Map.of("kind", "note"), Map.of("kind", "memo"));
  }

  @Override
  protected void assertListPlan() {
    // A bulk tenant (30k rows) so the planner has a real choice; the medium seed replaces this in P03.
    UUID bulk = UUID.fromString("0000b01c-0000-7000-8000-000000000001");
    Long have = app.admin.sql("SELECT count(1) FROM demo.notes WHERE account_id = :a").param("a", bulk)
        .query(Long.class).single();
    if (have < 30_000) {
      seed(bulk, 30_000, null);
      app.admin.sql("ANALYZE demo.notes").update();
    }
    Tenant t = Tenant.user(bulk);
    app.tx.read(t, () -> PlanAssert.query(app.jdbc, NoteRepository.LIST.firstPageSql(), Map.of("__limit", 51))
        .forbidSeqScanOn("notes").forbidSort().requireIndex("notes_list").verify());
    app.tx.read(t, () -> PlanAssert.query(app.jdbc, NoteRepository.LIST.nextPageSql(), Map.of("__limit", 26,
            "__k0", Sql.ts(BASE.plus(1, ChronoUnit.DAYS)), "__k1", UUID.fromString("ffffffff-ffff-7fff-bfff-ffffffffffff")))
        .forbidSeqScanOn("notes").forbidSort().requireIndex("notes_list").verify());
  }
}
