package in.dexter.ledger.infra;

import in.dexter.core.error.DexterException;
import in.dexter.db.page.KeyType;
import in.dexter.db.page.KeysetPager;
import in.dexter.db.page.KeysetQuery;
import in.dexter.db.page.Page;
import in.dexter.db.page.PageRequest;
import in.dexter.db.sql.Rows;
import in.dexter.db.sql.Sql;
import in.dexter.db.tenant.TenantScope;
import in.dexter.ledger.app.ExpenseFilter;
import in.dexter.ledger.app.ExpenseRow;
import in.dexter.ledger.app.ExpenseStore;
import in.dexter.ledger.domain.LedgerErrors;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@code ledger.expenses} under RLS. The list is keyset-paged on {@code (occurred_at DESC, id DESC)} over live rows
 * ({@code expenses_list}); each combination of filters is its own {@link KeysetQuery} (built once, cached), so the
 * planner always sees exactly the predicates in use — never {@code :x IS NULL OR ...}.
 */
public final class ExpenseRepository implements ExpenseStore {
  static final String COLS = "id, account_id, occurred_at, amount_paise, currency, merchant_raw, merchant_display,"
      + " merchant_id, category_path::text AS category_path, category_source, resolution_confidence, payment_method,"
      + " user_card_id, note, tags, client_ref, source, created_at, updated_at, version, deleted_at";
  static final RowMapper<ExpenseRow> ROW = (rs, i) -> new ExpenseRow(Rows.uuid(rs, "id"), Rows.uuid(rs, "account_id"),
      Rows.instant(rs, "occurred_at"), rs.getLong("amount_paise"), rs.getString("currency"),
      rs.getString("merchant_raw"), rs.getString("merchant_display"), Rows.uuid(rs, "merchant_id"),
      rs.getString("category_path"), rs.getString("category_source"), Rows.nullableInt(rs, "resolution_confidence"),
      rs.getString("payment_method"), Rows.uuid(rs, "user_card_id"), rs.getString("note"),
      tags(Rows.textArray(rs, "tags")), rs.getString("client_ref"), rs.getString("source"),
      Rows.instant(rs, "created_at"), Rows.instant(rs, "updated_at"), rs.getLong("version"),
      Rows.instant(rs, "deleted_at"));
  static final String BASE = "SELECT " + COLS + " FROM ledger.expenses"
      + " WHERE account_id = (SELECT ledger.app_account()) AND deleted_at IS NULL";

  /** Filter bits, in a fixed order (bit i ↔ predicate i). */
  static final List<String> PREDICATES = List.of(
      " AND occurred_at >= :from",
      " AND occurred_at < :to",
      " AND category_path OPERATOR(public.<@) CAST(:category AS public.ltree)",
      " AND payment_method = :paymentMethod",
      " AND user_card_id = :cardId",
      " AND merchant_id = :merchantId",
      " AND amount_paise >= :minPaise",
      " AND amount_paise <= :maxPaise");

  private final JdbcClient jdbc;
  private final KeysetPager pager;
  private final Map<Integer, KeysetQuery<ExpenseRow>> queries = new ConcurrentHashMap<>();

  public ExpenseRepository(JdbcClient jdbc, KeysetPager pager) {
    this.jdbc = jdbc;
    this.pager = pager;
  }

  /** The list query for a set of filters (exposed for PlanAssert). */
  public static KeysetQuery<ExpenseRow> listQuery(int mask) {
    StringBuilder sql = new StringBuilder(BASE);
    for (int i = 0; i < PREDICATES.size(); i++) {
      if ((mask & (1 << i)) != 0) {
        sql.append(PREDICATES.get(i));
      }
    }
    return KeysetQuery.<ExpenseRow>builder("occurred_desc")
        .key("occurred_at", KeyType.TIMESTAMPTZ)
        .key("id", KeyType.UUID)
        .sql(sql.toString())
        .mapper(ROW)
        .keysOf(r -> List.of(r.occurredAt(), r.id()))
        .build();
  }

  public static int mask(ExpenseFilter f, Instant from, Instant to) {
    Object[] v = {from, to, f.category(), f.paymentMethod(), f.cardId(), f.merchantId(), f.minPaise(), f.maxPaise()};
    int m = 0;
    for (int i = 0; i < v.length; i++) {
      if (v[i] != null) {
        m |= 1 << i;
      }
    }
    return m;
  }

  @Override
  public Page<ExpenseRow> list(PageRequest page, ExpenseFilter f, Instant from, Instant to) {
    TenantScope.require();
    int mask = mask(f, from, to);
    Map<String, Object> params = new HashMap<>();
    if (from != null) {
      params.put("from", Sql.ts(from));
    }
    if (to != null) {
      params.put("to", Sql.ts(to));
    }
    put(params, "category", f.category());
    put(params, "paymentMethod", f.paymentMethod());
    put(params, "cardId", f.cardId());
    put(params, "merchantId", f.merchantId());
    put(params, "minPaise", f.minPaise());
    put(params, "maxPaise", f.maxPaise());
    return pager.page(jdbc, queries.computeIfAbsent(mask, ExpenseRepository::listQuery), page, params, f.asMap());
  }

  @Override
  public void insert(ExpenseRow r) {
    TenantScope.require();
    try {
      jdbc.sql("INSERT INTO ledger.expenses (id, account_id, occurred_at, amount_paise, currency, merchant_raw,"
              + " merchant_display, merchant_id, category_path, category_source, resolution_confidence, payment_method,"
              + " user_card_id, note, tags, client_ref, source, created_at, updated_at, version, deleted_at)"
              + " VALUES (:id, :a, :at, :amt, :cur, :mr, :md, :mid, CAST(:cp AS public.ltree), :cs, :rc, :pm, :card,"
              + " :note, :tags, :cref, :src, :c, :u, :v, :d)")
          .param("id", r.id()).param("a", r.accountId()).param("at", Sql.ts(r.occurredAt()))
          .param("amt", r.amountPaise()).param("cur", r.currency()).param("mr", r.merchantRaw())
          .param("md", r.merchantDisplay()).param("mid", r.merchantId()).param("cp", r.categoryPath())
          .param("cs", r.categorySource()).param("rc", r.resolutionConfidence()).param("pm", r.paymentMethod())
          .param("card", r.userCardId()).param("note", r.note())
          .param("tags", r.tags().isEmpty() ? null : Sql.texts(r.tags())).param("cref", r.clientRef())
          .param("src", r.source()).param("c", Sql.ts(r.createdAt())).param("u", Sql.ts(r.updatedAt()))
          .param("v", r.version()).param("d", r.deletedAt() == null ? null : Sql.ts(r.deletedAt()))
          .update();
    } catch (DuplicateKeyException e) {
      if (String.valueOf(e.getMessage()).contains("expenses_client_ref")) {
        throw new DexterException(LedgerErrors.CLIENT_REF_TAKEN, "Another expense already has this clientRef");
      }
      throw e;
    }
  }

  @Override
  public Optional<ExpenseRow> find(UUID id) {
    TenantScope.require();
    return jdbc.sql("SELECT " + COLS + " FROM ledger.expenses WHERE id = :id").param("id", id).query(ROW).optional();
  }

  @Override
  public Optional<ExpenseRow> lock(UUID id) {
    TenantScope.require();
    return jdbc.sql("SELECT " + COLS + " FROM ledger.expenses WHERE id = :id FOR UPDATE").param("id", id).query(ROW)
        .optional();
  }

  @Override
  public void update(ExpenseRow r) {
    TenantScope.require();
    jdbc.sql("UPDATE ledger.expenses SET occurred_at = :at, amount_paise = :amt, merchant_raw = :mr,"
            + " merchant_display = :md, merchant_id = :mid, category_path = CAST(:cp AS public.ltree),"
            + " category_source = :cs, resolution_confidence = :rc, payment_method = :pm, user_card_id = :card,"
            + " note = :note, tags = :tags, updated_at = :u, version = :v, deleted_at = :d WHERE id = :id")
        .param("at", Sql.ts(r.occurredAt())).param("amt", r.amountPaise()).param("mr", r.merchantRaw())
        .param("md", r.merchantDisplay()).param("mid", r.merchantId()).param("cp", r.categoryPath())
        .param("cs", r.categorySource()).param("rc", r.resolutionConfidence()).param("pm", r.paymentMethod())
        .param("card", r.userCardId()).param("note", r.note())
        .param("tags", r.tags().isEmpty() ? null : Sql.texts(r.tags())).param("u", Sql.ts(r.updatedAt()))
        .param("v", r.version()).param("d", r.deletedAt() == null ? null : Sql.ts(r.deletedAt()))
        .param("id", r.id())
        .update();
  }

  private static void put(Map<String, Object> m, String k, Object v) {
    if (v != null) {
      m.put(k, v);
    }
  }

  private static List<String> tags(String[] a) {
    return a == null ? List.of() : List.of(a);
  }
}
