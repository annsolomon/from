package in.dexter.portfolio.infra;

import in.dexter.db.page.KeyType;
import in.dexter.db.page.KeysetPager;
import in.dexter.db.page.KeysetQuery;
import in.dexter.db.page.Page;
import in.dexter.db.page.PageRequest;
import in.dexter.db.sql.Rows;
import in.dexter.db.sql.Sql;
import in.dexter.db.tenant.TenantScope;
import in.dexter.portfolio.app.EmiStore;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/** EMI plans and schedules (RLS: the tenant's rows). */
public final class EmiRepository implements EmiStore {
  static final String COLS = "id, account_id, user_card_id, card_txn_id, description, principal_paise,"
      + " annual_rate_bps, tenure_months, processing_fee_paise, gst_bps, first_due_date, emi_paise,"
      + " total_interest_paise, total_gst_paise, status, created_at, updated_at, version";
  static final RowMapper<Plan> PLAN = (rs, n) -> new Plan(Rows.uuid(rs, "id"), Rows.uuid(rs, "account_id"),
      Rows.uuid(rs, "user_card_id"), Rows.uuid(rs, "card_txn_id"), rs.getString("description"),
      rs.getLong("principal_paise"), rs.getInt("annual_rate_bps"), rs.getInt("tenure_months"),
      rs.getLong("processing_fee_paise"), rs.getInt("gst_bps"), Rows.date(rs, "first_due_date"),
      rs.getLong("emi_paise"), rs.getLong("total_interest_paise"), rs.getLong("total_gst_paise"),
      rs.getString("status"), Rows.instant(rs, "created_at"), Rows.instant(rs, "updated_at"), rs.getInt("version"));
  static final String SCH_COLS = "plan_id, installment_no, due_date, principal_paise, interest_paise, gst_paise,"
      + " total_paise, outstanding_after_paise, status";
  static final RowMapper<Installment> INSTALLMENT = (rs, n) -> new Installment(Rows.uuid(rs, "plan_id"),
      rs.getInt("installment_no"), Rows.date(rs, "due_date"), rs.getLong("principal_paise"),
      rs.getLong("interest_paise"), rs.getLong("gst_paise"), rs.getLong("total_paise"),
      rs.getLong("outstanding_after_paise"), rs.getString("status"));

  static final KeysetQuery<Plan> LIST = KeysetQuery.<Plan>builder("created_desc")
      .direction(KeysetQuery.Direction.DESC)
      .key("created_at", KeyType.TIMESTAMPTZ)
      .key("id", KeyType.UUID)
      .sql("SELECT " + COLS + " FROM portfolio.emi_plans WHERE account_id = (SELECT portfolio.app_account())")
      .mapper(PLAN)
      .keysOf(p -> List.of(p.createdAt(), p.id()))
      .build();
  /** One plan's instalments in order (emi_schedule_pkey). */
  static final KeysetQuery<Installment> SCHEDULE = KeysetQuery.<Installment>builder("installment_asc")
      .direction(KeysetQuery.Direction.ASC)
      .key("installment_no", KeyType.INTEGER)
      .key("plan_id", KeyType.UUID)
      .sql("SELECT " + SCH_COLS + " FROM portfolio.emi_schedule WHERE plan_id = :plan")
      .mapper(INSTALLMENT)
      .keysOf(i -> List.of(i.number(), i.planId()))
      .build();

  private final JdbcClient jdbc;
  private final KeysetPager pager;

  public EmiRepository(JdbcClient jdbc, KeysetPager pager) {
    this.jdbc = jdbc;
    this.pager = pager;
  }

  @Override
  public void insert(Plan p, List<Installment> schedule) {
    TenantScope.require();
    Map<String, Object> m = new HashMap<>();
    m.put("id", p.id());
    m.put("a", p.accountId());
    m.put("card", p.userCardId());
    m.put("txn", p.cardTxnId());
    m.put("desc", p.description());
    m.put("principal", p.principalPaise());
    m.put("rate", p.annualRateBps());
    m.put("tenure", p.tenureMonths());
    m.put("fee", p.processingFeePaise());
    m.put("gst", p.gstBps());
    m.put("first", p.firstDueDate());
    m.put("emi", p.emiPaise());
    m.put("interest", p.totalInterestPaise());
    m.put("tgst", p.totalGstPaise());
    m.put("status", p.status());
    m.put("now", Sql.ts(p.createdAt()));
    jdbc.sql("INSERT INTO portfolio.emi_plans (" + COLS + ") VALUES (:id, :a, :card, :txn, :desc, :principal, :rate,"
        + " :tenure, :fee, :gst, :first, :emi, :interest, :tgst, :status, :now, :now, 0)").params(m).update();
    for (Installment i : schedule) {
      jdbc.sql("INSERT INTO portfolio.emi_schedule (" + SCH_COLS + ", account_id) VALUES (:plan, :no, :due, :p,"
              + " :i, :g, :t, :out, :status, :a)")
          .param("plan", p.id()).param("no", i.number()).param("due", i.dueDate()).param("p", i.principalPaise())
          .param("i", i.interestPaise()).param("g", i.gstPaise()).param("t", i.totalPaise())
          .param("out", i.outstandingAfterPaise()).param("status", i.status()).param("a", p.accountId()).update();
    }
  }

  @Override
  public Optional<Plan> find(UUID id) {
    TenantScope.require();
    return jdbc.sql("SELECT " + COLS + " FROM portfolio.emi_plans WHERE id = :id").param("id", id).query(PLAN)
        .optional();
  }

  @Override
  public Optional<Plan> lock(UUID id) {
    TenantScope.require();
    return jdbc.sql("SELECT " + COLS + " FROM portfolio.emi_plans WHERE id = :id FOR UPDATE").param("id", id)
        .query(PLAN).optional();
  }

  @Override
  public void update(Plan p) {
    TenantScope.require();
    jdbc.sql("UPDATE portfolio.emi_plans SET description = :d, status = :s, updated_at = :now, version = :v"
            + " WHERE id = :id")
        .param("d", p.description()).param("s", p.status()).param("now", Sql.ts(p.updatedAt()))
        .param("v", p.version()).param("id", p.id()).update();
  }

  @Override
  public void cancelRemaining(UUID planId) {
    TenantScope.require();
    jdbc.sql("UPDATE portfolio.emi_schedule SET status = 'cancelled' WHERE plan_id = :p AND status = 'due'")
        .param("p", planId).update();
  }

  @Override
  public Page<Plan> list(PageRequest page) {
    TenantScope.require();
    return pager.page(jdbc, LIST, page, Map.of(), Map.of());
  }

  @Override
  public Page<Installment> schedule(PageRequest page, UUID planId) {
    TenantScope.require();
    return pager.page(jdbc, SCHEDULE, page, Map.of("plan", planId), Map.of("planId", planId.toString()));
  }
}
