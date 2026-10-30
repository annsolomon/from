package in.dexter.planning.infra;

import in.dexter.db.page.KeyType;
import in.dexter.db.page.KeysetPager;
import in.dexter.db.page.KeysetQuery;
import in.dexter.db.page.Page;
import in.dexter.db.page.PageRequest;
import in.dexter.db.sql.Rows;
import in.dexter.db.sql.Sql;
import in.dexter.db.tenant.TenantScope;
import in.dexter.planning.app.BudgetStore;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Budgets, period spend and sent alerts (RLS: the tenant's rows).
 *
 * <p>{@code alert_thresholds} is a {@code smallint[]} read and written as its Postgres array literal
 * ({@code {50,80,100}}): a plain string either way, so no driver array plumbing and no element-type surprises.
 */
public final class BudgetRepository implements BudgetStore {
  static final String COLS = "id, account_id, name, period, category_path, limit_paise, rollover,"
      + " alert_thresholds, starts_on, ends_on, status, created_at, updated_at, version";
  /** The same columns for a SELECT, with the two that need a cast to come back as text. */
  static final String SELECT = "id, account_id, name, period, category_path::text AS category_path, limit_paise,"
      + " rollover, alert_thresholds::text AS alert_thresholds, starts_on, ends_on, status, created_at, updated_at,"
      + " version";
  static final RowMapper<BudgetRow> BUDGET = (rs, n) -> new BudgetRow(Rows.uuid(rs, "id"),
      Rows.uuid(rs, "account_id"), rs.getString("name"), rs.getString("period"), rs.getString("category_path"),
      rs.getLong("limit_paise"), rs.getBoolean("rollover"), thresholds(rs.getString("alert_thresholds")),
      Rows.date(rs, "starts_on"), Rows.date(rs, "ends_on"), rs.getString("status"), Rows.instant(rs, "created_at"),
      Rows.instant(rs, "updated_at"), rs.getInt("version"));
  static final String SPEND_COLS = "budget_id, period_start, spent_paise, projected_version, updated_at";
  static final RowMapper<SpendRow> SPEND = (rs, n) -> new SpendRow(Rows.uuid(rs, "budget_id"),
      Rows.date(rs, "period_start"), rs.getLong("spent_paise"), rs.getLong("projected_version"),
      Rows.instant(rs, "updated_at"));

  /** A tenant's budgets newest first ({@code budgets_list}); public so `BudgetsPaginationContractIT` can plan it. */
  public static final KeysetQuery<BudgetRow> LIST = KeysetQuery.<BudgetRow>builder("created_desc")
      .direction(KeysetQuery.Direction.DESC)
      .key("created_at", KeyType.TIMESTAMPTZ)
      .key("id", KeyType.UUID)
      .sql("SELECT " + SELECT + " FROM planning.budgets WHERE account_id = (SELECT planning.app_account())")
      .mapper(BUDGET)
      .keysOf(b -> List.of(b.createdAt(), b.id()))
      .build();
  /** One budget's periods, newest first (budget_period_spend_pkey walked backwards). */
  static final KeysetQuery<SpendRow> HISTORY = KeysetQuery.<SpendRow>builder("period_desc")
      .direction(KeysetQuery.Direction.DESC)
      .key("period_start", KeyType.DATE)
      .key("budget_id", KeyType.UUID)
      .sql("SELECT " + SPEND_COLS + " FROM planning.budget_period_spend WHERE budget_id = :budget")
      .mapper(SPEND)
      .keysOf(s -> List.of(s.periodStart(), s.budgetId()))
      .build();

  private final JdbcClient jdbc;
  private final KeysetPager pager;

  public BudgetRepository(JdbcClient jdbc, KeysetPager pager) {
    this.jdbc = jdbc;
    this.pager = pager;
  }

  @Override
  public void insert(BudgetRow b) {
    TenantScope.require();
    Map<String, Object> m = new HashMap<>();
    m.put("id", b.id());
    m.put("a", b.accountId());
    m.put("name", b.name());
    m.put("period", b.period());
    m.put("cat", b.categoryPath() == null ? null : Sql.ltree(b.categoryPath()));
    m.put("limit", b.limitPaise());
    m.put("rollover", b.rollover());
    m.put("thr", literal(b.alertThresholds()));
    m.put("starts", b.startsOn());
    m.put("ends", b.endsOn());
    m.put("status", b.status());
    m.put("now", Sql.ts(b.createdAt()));
    jdbc.sql("INSERT INTO planning.budgets (" + COLS + ") VALUES (:id, :a, :name, :period, :cat, :limit,"
        + " :rollover, :thr::smallint[], :starts, :ends, :status, :now, :now, 0)").params(m).update();
  }

  @Override
  public Optional<BudgetRow> find(UUID id) {
    TenantScope.require();
    return jdbc.sql("SELECT " + SELECT + " FROM planning.budgets WHERE id = :id").param("id", id).query(BUDGET)
        .optional();
  }

  @Override
  public Optional<BudgetRow> lock(UUID id) {
    TenantScope.require();
    return jdbc.sql("SELECT " + SELECT + " FROM planning.budgets WHERE id = :id FOR UPDATE").param("id", id)
        .query(BUDGET).optional();
  }

  @Override
  public void update(BudgetRow b) {
    TenantScope.require();
    jdbc.sql("UPDATE planning.budgets SET name = :name, limit_paise = :limit, rollover = :rollover,"
            + " alert_thresholds = :thr::smallint[], ends_on = :ends, status = :status, updated_at = :now,"
            + " version = :v WHERE id = :id")
        .param("name", b.name()).param("limit", b.limitPaise()).param("rollover", b.rollover())
        .param("thr", literal(b.alertThresholds())).param("ends", b.endsOn()).param("status", b.status())
        .param("now", Sql.ts(b.updatedAt())).param("v", b.version()).param("id", b.id()).update();
  }

  @Override
  public Page<BudgetRow> list(PageRequest page) {
    TenantScope.require();
    return pager.page(jdbc, LIST, page, Map.of(), Map.of());
  }

  @Override
  public List<BudgetRow> active() {
    TenantScope.require();
    return jdbc.sql("SELECT " + SELECT + " FROM planning.budgets"
        + " WHERE account_id = (SELECT planning.app_account()) AND status = 'active'"
        + " ORDER BY created_at, id").query(BUDGET).list();
  }

  @Override
  public Optional<SpendRow> spend(UUID budgetId, LocalDate periodStart) {
    TenantScope.require();
    return jdbc.sql("SELECT " + SPEND_COLS + " FROM planning.budget_period_spend"
            + " WHERE budget_id = :b AND period_start = :p")
        .param("b", budgetId).param("p", periodStart).query(SPEND).optional();
  }

  @Override
  public Page<SpendRow> history(PageRequest page, UUID budgetId) {
    TenantScope.require();
    // the path id scopes the rows; the cursor binds query filters only (A-66)
    return pager.page(jdbc, HISTORY, page, Map.of("budget", budgetId), Map.of());
  }

  @Override
  public long addSpend(UUID accountId, UUID budgetId, LocalDate periodStart, long deltaPaise, long version) {
    TenantScope.require();
    // GREATEST on the inserted row too: Postgres checks the proposed INSERT against the CHECK before it falls
    // through to ON CONFLICT (A-47), so a first-ever negative delta would otherwise fail the constraint.
    return jdbc.sql("INSERT INTO planning.budget_period_spend"
            + " (account_id, budget_id, period_start, spent_paise, projected_version, updated_at)"
            + " VALUES (:a, :b, :p, GREATEST(0, :delta), :v, now())"
            + " ON CONFLICT (account_id, budget_id, period_start) DO UPDATE"
            + " SET spent_paise = GREATEST(0, budget_period_spend.spent_paise + :delta),"
            + " projected_version = GREATEST(budget_period_spend.projected_version, :v), updated_at = now()"
            + " RETURNING spent_paise")
        .param("a", accountId).param("b", budgetId).param("p", periodStart).param("delta", deltaPaise)
        .param("v", version).query(Long.class).single();
  }

  @Override
  public List<Integer> alertsSent(UUID budgetId, LocalDate periodStart) {
    TenantScope.require();
    return jdbc.sql("SELECT threshold FROM planning.budget_alerts_sent"
            + " WHERE budget_id = :b AND period_start = :p ORDER BY threshold")
        .param("b", budgetId).param("p", periodStart).query(Integer.class).list();
  }

  @Override
  public boolean markAlertSent(UUID accountId, UUID budgetId, LocalDate periodStart, int threshold,
      long spentPaise) {
    TenantScope.require();
    // the primary key is what makes it once per period: a second attempt inserts nothing
    return jdbc.sql("INSERT INTO planning.budget_alerts_sent"
            + " (account_id, budget_id, period_start, threshold, spent_paise)"
            + " VALUES (:a, :b, :p, :t, :spent) ON CONFLICT DO NOTHING")
        .param("a", accountId).param("b", budgetId).param("p", periodStart).param("t", threshold)
        .param("spent", spentPaise).update() == 1;
  }

  /** {@code {50,80,100}} — the Postgres array literal, cast to smallint[] in the statement. */
  static String literal(List<Integer> thresholds) {
    StringBuilder sb = new StringBuilder("{");
    for (int i = 0; i < thresholds.size(); i++) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append(thresholds.get(i).intValue());
    }
    return sb.append('}').toString();
  }

  static List<Integer> thresholds(String literal) {
    if (literal == null) {
      return List.of();
    }
    String body = literal.replace("{", "").replace("}", "").trim();
    if (body.isEmpty()) {
      return List.of();
    }
    List<Integer> out = new ArrayList<>();
    for (String part : body.split(",")) {
      out.add(Integer.valueOf(part.trim()));
    }
    return List.copyOf(out);
  }
}
