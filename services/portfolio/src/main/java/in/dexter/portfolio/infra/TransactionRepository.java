package in.dexter.portfolio.infra;

import in.dexter.db.page.KeyType;
import in.dexter.db.page.KeysetPager;
import in.dexter.db.page.KeysetQuery;
import in.dexter.db.page.Page;
import in.dexter.db.page.PageRequest;
import in.dexter.db.sql.Rows;
import in.dexter.db.sql.Sql;
import in.dexter.db.tenant.TenantScope;
import in.dexter.portfolio.app.TransactionStore;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Card transactions, statements and monthly savings (RLS: the tenant's rows). */
public final class TransactionRepository implements TransactionStore {
  static final String TXN_COLS = "id, account_id, user_card_id, statement_id, posted_at, amount_paise, direction, kind,"
      + " description, merchant_id, category_path::text AS category_path, expense_id, client_ref, source, created_at,"
      + " version";
  static final RowMapper<TxnRow> TXN = (rs, n) -> new TxnRow(Rows.uuid(rs, "id"), Rows.uuid(rs, "account_id"),
      Rows.uuid(rs, "user_card_id"), Rows.uuid(rs, "statement_id"), Rows.instant(rs, "posted_at"),
      rs.getLong("amount_paise"), rs.getString("direction"), rs.getString("kind"), rs.getString("description"),
      Rows.uuid(rs, "merchant_id"), rs.getString("category_path"), Rows.uuid(rs, "expense_id"),
      rs.getString("client_ref"), rs.getString("source"), Rows.instant(rs, "created_at"), rs.getInt("version"));
  static final String ST_COLS = "id, account_id, user_card_id, period_start, period_end, due_date, debits_paise,"
      + " credits_paise, txn_count, total_due_paise, min_due_paise, status, created_at, updated_at, version";
  static final RowMapper<StatementRow> STATEMENT = (rs, n) -> new StatementRow(Rows.uuid(rs, "id"),
      Rows.uuid(rs, "account_id"), Rows.uuid(rs, "user_card_id"), Rows.date(rs, "period_start"),
      Rows.date(rs, "period_end"), Rows.date(rs, "due_date"), rs.getLong("debits_paise"), rs.getLong("credits_paise"),
      rs.getInt("txn_count"), rs.getLong("total_due_paise"), rs.getLong("min_due_paise"), rs.getString("status"),
      Rows.instant(rs, "created_at"), Rows.instant(rs, "updated_at"), rs.getInt("version"));

  /** A card's transactions newest first (card_transactions_list). */
  public static final KeysetQuery<TxnRow> LIST = KeysetQuery.<TxnRow>builder("posted_desc")
      .direction(KeysetQuery.Direction.DESC)
      .key("posted_at", KeyType.TIMESTAMPTZ)
      .key("id", KeyType.UUID)
      .sql("SELECT " + TXN_COLS + " FROM portfolio.card_transactions"
          + " WHERE account_id = (SELECT portfolio.app_account()) AND user_card_id = :card")
      .mapper(TXN)
      .keysOf(t -> List.of(t.postedAt(), t.id()))
      .build();
  /** A card's statements, newest cycle first (statements_list). */
  static final KeysetQuery<StatementRow> STATEMENTS = KeysetQuery.<StatementRow>builder("period_desc")
      .direction(KeysetQuery.Direction.DESC)
      .key("period_start", KeyType.DATE)
      .key("id", KeyType.UUID)
      .sql("SELECT " + ST_COLS + " FROM portfolio.statements"
          + " WHERE account_id = (SELECT portfolio.app_account()) AND user_card_id = :card")
      .mapper(STATEMENT)
      .keysOf(s -> List.of(s.periodStart(), s.id()))
      .build();

  private final JdbcClient jdbc;
  private final KeysetPager pager;

  public TransactionRepository(JdbcClient jdbc, KeysetPager pager) {
    this.jdbc = jdbc;
    this.pager = pager;
  }

  @Override
  public void insert(TxnRow t) {
    TenantScope.require();
    Map<String, Object> m = new HashMap<>();
    m.put("id", t.id());
    m.put("a", t.accountId());
    m.put("card", t.userCardId());
    m.put("st", t.statementId());
    m.put("at", Sql.ts(t.postedAt()));
    m.put("amount", t.amountPaise());
    m.put("dir", t.direction());
    m.put("kind", t.kind());
    m.put("desc", t.description());
    m.put("merchant", t.merchantId());
    m.put("cat", t.categoryPath() == null ? null : Sql.ltree(t.categoryPath()));
    m.put("expense", t.expenseId());
    m.put("ref", t.clientRef());
    m.put("source", t.source());
    m.put("created", Sql.ts(t.createdAt()));
    jdbc.sql("INSERT INTO portfolio.card_transactions (id, account_id, user_card_id, statement_id, posted_at,"
            + " amount_paise, direction, kind, description, merchant_id, category_path, expense_id, client_ref, source,"
            + " created_at) VALUES (:id, :a, :card, :st, :at, :amount, :dir, :kind, :desc, :merchant, :cat, :expense,"
            + " :ref, :source, :created)")
        .params(m).update();
  }

  @Override
  public Optional<TxnRow> find(UUID id) {
    TenantScope.require();
    return jdbc.sql("SELECT " + TXN_COLS + " FROM portfolio.card_transactions WHERE id = :id").param("id", id)
        .query(TXN).optional();
  }

  @Override
  public Optional<TxnRow> lock(UUID id) {
    TenantScope.require();
    return jdbc.sql("SELECT " + TXN_COLS + " FROM portfolio.card_transactions WHERE id = :id FOR UPDATE")
        .param("id", id).query(TXN).optional();
  }

  @Override
  public Optional<UUID> idByClientRef(String clientRef) {
    TenantScope.require();
    return jdbc.sql("SELECT id FROM portfolio.card_transactions WHERE account_id = (SELECT portfolio.app_account())"
        + " AND client_ref = :r").param("r", clientRef).query(UUID.class).optional();
  }

  @Override
  public Page<TxnRow> list(PageRequest page, UUID cardId) {
    TenantScope.require();
    return pager.page(jdbc, LIST, page, Map.of("card", cardId), Map.of("cardId", cardId.toString()));
  }

  @Override
  public void setExpense(UUID txnId, UUID expenseId) {
    TenantScope.require();
    jdbc.sql("UPDATE portfolio.card_transactions SET expense_id = :e, version = version + 1 WHERE id = :id")
        .param("e", expenseId).param("id", txnId).update();
  }

  @Override
  public List<TxnRow> matchCandidates(UUID cardId, long amountPaise, Instant from, Instant to) {
    TenantScope.require();
    return jdbc.sql("SELECT " + TXN_COLS + " FROM portfolio.card_transactions t"
            + " WHERE t.account_id = (SELECT portfolio.app_account()) AND t.user_card_id = :card"
            + " AND t.amount_paise = :amount AND t.posted_at >= :from AND t.posted_at < :to AND t.expense_id IS NULL"
            + " AND t.direction = 'debit' AND NOT EXISTS (SELECT 1 FROM portfolio.expense_links l"
            + " WHERE l.card_txn_id = t.id AND l.status = 'confirmed')"
            + " ORDER BY t.posted_at, t.id LIMIT 50")
        .param("card", cardId).param("amount", amountPaise).param("from", Sql.ts(from)).param("to", Sql.ts(to))
        .query(TXN).list();
  }

  // ------------------------------------------------------------------ statements

  @Override
  public Optional<StatementRow> lockStatement(UUID cardId, LocalDate periodStart) {
    TenantScope.require();
    return jdbc.sql("SELECT " + ST_COLS + " FROM portfolio.statements WHERE user_card_id = :card"
            + " AND period_start = :start FOR UPDATE")
        .param("card", cardId).param("start", periodStart).query(STATEMENT).optional();
  }

  @Override
  public boolean insertStatement(StatementRow s) {
    TenantScope.require();
    return jdbc.sql("INSERT INTO portfolio.statements (id, account_id, user_card_id, period_start, period_end,"
            + " due_date, status, created_at, updated_at) VALUES (:id, :a, :card, :start, :end, :due, 'open', :now,"
            + " :now) ON CONFLICT (user_card_id, period_start) DO NOTHING")
        .param("id", s.id()).param("a", s.accountId()).param("card", s.userCardId()).param("start", s.periodStart())
        .param("end", s.periodEnd()).param("due", s.dueDate()).param("now", Sql.ts(s.createdAt()))
        .update() == 1;
  }

  @Override
  public void addToStatement(UUID statementId, long debitPaise, long creditPaise, Instant now) {
    TenantScope.require();
    jdbc.sql("UPDATE portfolio.statements SET debits_paise = debits_paise + :d, credits_paise = credits_paise + :c,"
            + " txn_count = txn_count + 1, total_due_paise = total_due_paise + :d - :c, updated_at = :now,"
            + " version = version + 1 WHERE id = :id")
        .param("d", debitPaise).param("c", creditPaise).param("now", Sql.ts(now)).param("id", statementId).update();
  }

  @Override
  public Page<StatementRow> statements(PageRequest page, UUID cardId) {
    TenantScope.require();
    return pager.page(jdbc, STATEMENTS, page, Map.of("card", cardId), Map.of("cardId", cardId.toString()));
  }

  @Override
  public Optional<StatementRow> statement(UUID id) {
    TenantScope.require();
    return jdbc.sql("SELECT " + ST_COLS + " FROM portfolio.statements WHERE id = :id").param("id", id)
        .query(STATEMENT).optional();
  }

  @Override
  public int closeEndedStatements(LocalDate today, int limit) {
    TenantScope.require();
    return jdbc.sql("SELECT portfolio.close_ended_statements(:today, :limit)").param("today", today)
        .param("limit", limit).query(Integer.class).single();
  }

  // ------------------------------------------------------------------ savings

  @Override
  public void addSavings(UUID accountId, UUID cardId, LocalDate period, long spendPaise, int txnCount,
      long rewardsValuePaise, long feesPaise) {
    TenantScope.require();
    jdbc.sql("INSERT INTO portfolio.card_savings_summary (account_id, user_card_id, period, spend_paise, txn_count,"
            + " rewards_value_paise, fees_paise) VALUES (:a, :card, :p, :spend, :n, :rewards, :fees)"
            + " ON CONFLICT (account_id, user_card_id, period) DO UPDATE SET"
            + " spend_paise = card_savings_summary.spend_paise + EXCLUDED.spend_paise,"
            + " txn_count = card_savings_summary.txn_count + EXCLUDED.txn_count,"
            + " rewards_value_paise = card_savings_summary.rewards_value_paise + EXCLUDED.rewards_value_paise,"
            + " fees_paise = card_savings_summary.fees_paise + EXCLUDED.fees_paise")
        .param("a", accountId).param("card", cardId).param("p", period).param("spend", spendPaise)
        .param("n", txnCount).param("rewards", rewardsValuePaise).param("fees", feesPaise).update();
  }

  @Override
  public List<Savings> savings(LocalDate fromMonth, LocalDate toMonth) {
    TenantScope.require();
    return jdbc.sql("SELECT user_card_id, period, spend_paise, txn_count, rewards_value_paise, fees_paise"
            + " FROM portfolio.card_savings_summary WHERE account_id = (SELECT portfolio.app_account())"
            + " AND period BETWEEN :from AND :to ORDER BY user_card_id, period")
        .param("from", fromMonth).param("to", toMonth)
        .query((rs, n) -> new Savings(Rows.uuid(rs, "user_card_id"), Rows.date(rs, "period"), rs.getLong("spend_paise"),
            rs.getInt("txn_count"), rs.getLong("rewards_value_paise"), rs.getLong("fees_paise")))
        .list();
  }
}
