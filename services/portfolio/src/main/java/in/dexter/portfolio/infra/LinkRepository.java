package in.dexter.portfolio.infra;

import in.dexter.db.page.KeyType;
import in.dexter.db.page.KeysetPager;
import in.dexter.db.page.KeysetQuery;
import in.dexter.db.page.Page;
import in.dexter.db.page.PageRequest;
import in.dexter.db.sql.Rows;
import in.dexter.db.sql.Sql;
import in.dexter.db.tenant.TenantScope;
import in.dexter.events.ProjectionVersionGuard;
import in.dexter.portfolio.app.LinkStore;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Expense links and the ledger expense projection (RLS: the tenant's rows). */
public final class LinkRepository implements LinkStore {
  static final String LINK_COLS = "l.id, l.account_id, l.expense_id, l.card_txn_id, l.status, l.score, l.method,"
      + " l.created_at, l.decided_at, l.version";
  static final RowMapper<Link> LINK = (rs, n) -> new Link(Rows.uuid(rs, "id"), Rows.uuid(rs, "account_id"),
      Rows.uuid(rs, "expense_id"), Rows.uuid(rs, "card_txn_id"), rs.getString("status"),
      rs.getBigDecimal("score").doubleValue(), rs.getString("method"), Rows.instant(rs, "created_at"),
      Rows.instant(rs, "decided_at"), rs.getInt("version"));
  static final String EXP_COLS = "expense_id, account_id, user_card_id, amount_paise, day, merchant, merchant_id,"
      + " category_path, deleted, projected_version";
  static final RowMapper<ExpenseProj> EXPENSE = (rs, n) -> new ExpenseProj(Rows.uuid(rs, "expense_id"),
      Rows.uuid(rs, "account_id"), Rows.uuid(rs, "user_card_id"), rs.getLong("amount_paise"), Rows.date(rs, "day"),
      rs.getString("merchant"), Rows.uuid(rs, "merchant_id"), rs.getString("category_path"), rs.getBoolean("deleted"),
      rs.getLong("projected_version"));

  /** Open suggestions newest first (expense_links_suggested), with both sides. */
  public static final KeysetQuery<Suggestion> SUGGESTIONS = KeysetQuery.<Suggestion>builder("created_desc")
      .direction(KeysetQuery.Direction.DESC)
      .key("l.created_at", KeyType.TIMESTAMPTZ)
      .key("l.id", KeyType.UUID)
      .sql("SELECT " + LINK_COLS + ", t.user_card_id, t.posted_at, t.amount_paise, t.description, e.day AS e_day,"
          + " e.merchant AS e_merchant FROM portfolio.expense_links l"
          + " JOIN portfolio.card_transactions t ON t.id = l.card_txn_id"
          + " LEFT JOIN portfolio.ledger_expenses_proj e ON e.expense_id = l.expense_id"
          + " WHERE l.account_id = (SELECT portfolio.app_account()) AND l.status = 'suggested'")
      .mapper((rs, n) -> new Suggestion(LINK.mapRow(rs, n), Rows.uuid(rs, "user_card_id"), Rows.instant(rs, "posted_at"),
          rs.getLong("amount_paise"), rs.getString("description"), Rows.date(rs, "e_day"), rs.getString("e_merchant")))
      .keysOf(s -> List.of(s.link().createdAt(), s.link().id()))
      .build();

  static final String UPSERT_EXPENSE = "INSERT INTO portfolio.ledger_expenses_proj (" + EXP_COLS + ", updated_at)"
      + " VALUES (:id, :a, :card, :amount, :day, :merchant, :mid, :cat, :deleted, :version, now())"
      + " ON CONFLICT (expense_id) DO UPDATE SET user_card_id = EXCLUDED.user_card_id,"
      + " amount_paise = EXCLUDED.amount_paise, day = EXCLUDED.day, merchant = EXCLUDED.merchant,"
      + " merchant_id = EXCLUDED.merchant_id, category_path = EXCLUDED.category_path, deleted = EXCLUDED.deleted,"
      + " projected_version = EXCLUDED.projected_version, updated_at = now()"
      + " WHERE portfolio.ledger_expenses_proj.projected_version < EXCLUDED.projected_version";

  private final JdbcClient jdbc;
  private final KeysetPager pager;

  public LinkRepository(JdbcClient jdbc, KeysetPager pager) {
    this.jdbc = jdbc;
    this.pager = pager;
  }

  @Override
  public boolean upsertExpense(ExpenseProj e) {
    TenantScope.require();
    Map<String, Object> m = new HashMap<>();
    m.put("id", e.expenseId());
    m.put("a", e.accountId());
    m.put("card", e.userCardId());
    m.put("amount", e.amountPaise());
    m.put("day", e.day());
    m.put("merchant", e.merchant());
    m.put("mid", e.merchantId());
    m.put("cat", e.categoryPath());
    m.put("deleted", e.deleted());
    return ProjectionVersionGuard.apply(jdbc, UPSERT_EXPENSE, m, e.projectedVersion());
  }

  @Override
  public Optional<ExpenseProj> expense(UUID expenseId) {
    TenantScope.require();
    return jdbc.sql("SELECT " + EXP_COLS + " FROM portfolio.ledger_expenses_proj WHERE expense_id = :id")
        .param("id", expenseId).query(EXPENSE).optional();
  }

  @Override
  public List<ExpenseProj> matchCandidates(UUID cardId, long amountPaise, LocalDate from, LocalDate to) {
    TenantScope.require();
    return jdbc.sql("SELECT " + EXP_COLS + " FROM portfolio.ledger_expenses_proj e"
            + " WHERE e.account_id = (SELECT portfolio.app_account()) AND e.user_card_id = :card"
            + " AND e.amount_paise = :amount AND e.day BETWEEN :from AND :to AND NOT e.deleted"
            + " AND NOT EXISTS (SELECT 1 FROM portfolio.expense_links l WHERE l.account_id = e.account_id"
            + " AND l.expense_id = e.expense_id AND l.status = 'confirmed')"
            + " ORDER BY e.day, e.expense_id LIMIT 50")
        .param("card", cardId).param("amount", amountPaise).param("from", from).param("to", to)
        .query(EXPENSE).list();
  }

  @Override
  public boolean suggest(Link l) {
    TenantScope.require();
    return jdbc.sql("INSERT INTO portfolio.expense_links (id, account_id, expense_id, card_txn_id, status, score,"
            + " method, created_at) VALUES (:id, :a, :e, :t, 'suggested', :score, :method, :now)"
            + " ON CONFLICT (account_id, expense_id, card_txn_id) DO NOTHING")
        .param("id", l.id()).param("a", l.accountId()).param("e", l.expenseId()).param("t", l.cardTxnId())
        .param("score", BigDecimal.valueOf(Math.max(0, Math.min(1, l.score()))).setScale(3, RoundingMode.HALF_UP))
        .param("method", l.method()).param("now", Sql.ts(l.createdAt()))
        .update() == 1;
  }

  @Override
  public Page<Suggestion> suggestions(PageRequest page) {
    TenantScope.require();
    return pager.page(jdbc, SUGGESTIONS, page, Map.of(), Map.of());
  }

  @Override
  public Optional<Link> lock(UUID id) {
    TenantScope.require();
    return jdbc.sql("SELECT " + LINK_COLS + " FROM portfolio.expense_links l WHERE l.id = :id FOR UPDATE")
        .param("id", id).query(LINK).optional();
  }

  @Override
  public void decide(UUID id, String status, Instant now) {
    TenantScope.require();
    jdbc.sql("UPDATE portfolio.expense_links SET status = :s, decided_at = :now, version = version + 1"
        + " WHERE id = :id").param("s", status).param("now", Sql.ts(now)).param("id", id).update();
  }

  @Override
  public boolean confirmedForTxn(UUID txnId) {
    TenantScope.require();
    return jdbc.sql("SELECT count(1) FROM portfolio.expense_links WHERE card_txn_id = :t AND status = 'confirmed'")
        .param("t", txnId).query(Long.class).single() > 0;
  }

  @Override
  public boolean confirmedForExpense(UUID expenseId) {
    TenantScope.require();
    return jdbc.sql("SELECT count(1) FROM portfolio.expense_links WHERE account_id = (SELECT portfolio.app_account())"
        + " AND expense_id = :e AND status = 'confirmed'").param("e", expenseId).query(Long.class).single() > 0;
  }

  @Override
  public int rejectRivals(UUID linkId, UUID expenseId, UUID txnId, Instant now) {
    TenantScope.require();
    return jdbc.sql("UPDATE portfolio.expense_links SET status = 'rejected', decided_at = :now, version = version + 1"
            + " WHERE account_id = (SELECT portfolio.app_account()) AND status = 'suggested' AND id <> :id"
            + " AND (expense_id = :e OR card_txn_id = :t)")
        .param("now", Sql.ts(now)).param("id", linkId).param("e", expenseId).param("t", txnId).update();
  }

  @Override
  public int rejectOpenFor(UUID expenseId, Instant now) {
    TenantScope.require();
    return jdbc.sql("UPDATE portfolio.expense_links SET status = 'rejected', decided_at = :now, version = version + 1"
            + " WHERE account_id = (SELECT portfolio.app_account()) AND status = 'suggested' AND expense_id = :e")
        .param("now", Sql.ts(now)).param("e", expenseId).update();
  }
}
