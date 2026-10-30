package in.dexter.planning.infra;

import in.dexter.db.page.KeyType;
import in.dexter.db.page.KeysetPager;
import in.dexter.db.page.KeysetQuery;
import in.dexter.db.page.Page;
import in.dexter.db.page.PageRequest;
import in.dexter.db.sql.Rows;
import in.dexter.db.sql.Sql;
import in.dexter.db.tenant.TenantScope;
import in.dexter.planning.app.LiabilityStore;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Liabilities and their schedules (RLS: the tenant's rows). */
public final class LiabilityRepository implements LiabilityStore {
  static final String COLS = "id, account_id, kind, lender, principal_paise, apr_bps, tenure_months, start_date,"
      + " emi_paise, status, created_at, updated_at, version";
  static final RowMapper<LiabilityRow> LIABILITY = (rs, n) -> new LiabilityRow(Rows.uuid(rs, "id"),
      Rows.uuid(rs, "account_id"), rs.getString("kind"), rs.getString("lender"), rs.getLong("principal_paise"),
      rs.getInt("apr_bps"), rs.getInt("tenure_months"), Rows.date(rs, "start_date"), rs.getLong("emi_paise"),
      rs.getString("status"), Rows.instant(rs, "created_at"), Rows.instant(rs, "updated_at"), rs.getInt("version"));
  static final String SCH_COLS = "liability_id, installment_no, due_on, principal_paise, interest_paise,"
      + " total_paise, outstanding_after_paise";
  static final RowMapper<InstallmentRow> INSTALLMENT = (rs, n) -> new InstallmentRow(
      Rows.uuid(rs, "liability_id"), rs.getInt("installment_no"), Rows.date(rs, "due_on"),
      rs.getLong("principal_paise"), rs.getLong("interest_paise"), rs.getLong("total_paise"),
      rs.getLong("outstanding_after_paise"));

  static final KeysetQuery<LiabilityRow> LIST = KeysetQuery.<LiabilityRow>builder("created_desc")
      .direction(KeysetQuery.Direction.DESC)
      .key("created_at", KeyType.TIMESTAMPTZ)
      .key("id", KeyType.UUID)
      .sql("SELECT " + COLS + " FROM planning.liabilities WHERE account_id = (SELECT planning.app_account())")
      .mapper(LIABILITY)
      .keysOf(l -> List.of(l.createdAt(), l.id()))
      .build();
  /** One liability's instalments in order (liability_schedule_pkey). */
  static final KeysetQuery<InstallmentRow> SCHEDULE = KeysetQuery.<InstallmentRow>builder("installment_asc")
      .direction(KeysetQuery.Direction.ASC)
      .key("installment_no", KeyType.INTEGER)
      .key("liability_id", KeyType.UUID)
      .sql("SELECT " + SCH_COLS + " FROM planning.liability_schedule WHERE liability_id = :liability")
      .mapper(INSTALLMENT)
      .keysOf(i -> List.of(i.number(), i.liabilityId()))
      .build();

  private final JdbcClient jdbc;
  private final KeysetPager pager;

  public LiabilityRepository(JdbcClient jdbc, KeysetPager pager) {
    this.jdbc = jdbc;
    this.pager = pager;
  }

  @Override
  public void insert(LiabilityRow l, List<InstallmentRow> schedule) {
    TenantScope.require();
    Map<String, Object> m = new HashMap<>();
    m.put("id", l.id());
    m.put("a", l.accountId());
    m.put("kind", l.kind());
    m.put("lender", l.lender());
    m.put("principal", l.principalPaise());
    m.put("apr", l.aprBps());
    m.put("tenure", l.tenureMonths());
    m.put("start", l.startDate());
    m.put("emi", l.emiPaise());
    m.put("status", l.status());
    m.put("now", Sql.ts(l.createdAt()));
    jdbc.sql("INSERT INTO planning.liabilities (" + COLS + ") VALUES (:id, :a, :kind, :lender, :principal, :apr,"
        + " :tenure, :start, :emi, :status, :now, :now, 0)").params(m).update();
    for (InstallmentRow i : schedule) {
      jdbc.sql("INSERT INTO planning.liability_schedule (" + SCH_COLS + ", account_id) VALUES (:l, :no, :due, :p,"
              + " :i, :t, :out, :a)")
          .param("l", l.id()).param("no", i.number()).param("due", i.dueOn()).param("p", i.principalPaise())
          .param("i", i.interestPaise()).param("t", i.totalPaise()).param("out", i.outstandingAfterPaise())
          .param("a", l.accountId()).update();
    }
  }

  @Override
  public Optional<LiabilityRow> find(UUID id) {
    TenantScope.require();
    return jdbc.sql("SELECT " + COLS + " FROM planning.liabilities WHERE id = :id").param("id", id)
        .query(LIABILITY).optional();
  }

  @Override
  public Optional<LiabilityRow> lock(UUID id) {
    TenantScope.require();
    return jdbc.sql("SELECT " + COLS + " FROM planning.liabilities WHERE id = :id FOR UPDATE").param("id", id)
        .query(LIABILITY).optional();
  }

  @Override
  public void update(LiabilityRow l) {
    TenantScope.require();
    jdbc.sql("UPDATE planning.liabilities SET lender = :lender, status = :status, updated_at = :now, version = :v"
            + " WHERE id = :id")
        .param("lender", l.lender()).param("status", l.status()).param("now", Sql.ts(l.updatedAt()))
        .param("v", l.version()).param("id", l.id()).update();
  }

  @Override
  public Page<LiabilityRow> list(PageRequest page) {
    TenantScope.require();
    return pager.page(jdbc, LIST, page, Map.of(), Map.of());
  }

  @Override
  public List<LiabilityRow> active() {
    TenantScope.require();
    return jdbc.sql("SELECT " + COLS + " FROM planning.liabilities"
        + " WHERE account_id = (SELECT planning.app_account()) AND status = 'active'"
        + " ORDER BY created_at, id").query(LIABILITY).list();
  }

  @Override
  public Page<InstallmentRow> schedule(PageRequest page, UUID liabilityId) {
    TenantScope.require();
    // the path id scopes the rows; the cursor binds query filters only (A-66)
    return pager.page(jdbc, SCHEDULE, page, Map.of("liability", liabilityId), Map.of());
  }
}
