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
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

  /**
   * Search (MASTER_PLAN P08): the tenant + date btree ({@code expenses_list}) bounds the scan, then each row is
   * rechecked against the text (substring on merchant/note, pg_trgm word similarity on the merchant). No trigram
   * index on {@code expenses}: it would cover every tenant's rows and the predicate is per tenant (A-48).
   */
  public static final String SEARCH = BASE + " AND occurred_at >= :from AND occurred_at < :to"
      + " AND (merchant_display ILIKE :pat OR merchant_raw ILIKE :pat OR note ILIKE :pat"
      + " OR :q OPERATOR(public.<%) lower(coalesce(merchant_display, '')))";
  public static final KeysetQuery<ExpenseRow> SEARCH_QUERY = KeysetQuery.<ExpenseRow>builder("occurred_desc")
      .key("occurred_at", KeyType.TIMESTAMPTZ)
      .key("id", KeyType.UUID)
      .sql(SEARCH)
      .mapper(ROW)
      .keysOf(r -> List.of(r.occurredAt(), r.id()))
      .build();
  static final String INSERT_COLS = "(id, account_id, occurred_at, amount_paise, currency, merchant_raw,"
      + " merchant_display, merchant_id, category_path, category_source, resolution_confidence, payment_method,"
      + " user_card_id, note, tags, client_ref, source, created_at, updated_at, version, deleted_at)";
  static final int INSERT_BATCH = 500;

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
  public Page<ExpenseRow> search(PageRequest page, String q, Instant from, Instant to, Map<String, ?> filters) {
    TenantScope.require();
    Map<String, Object> params = new HashMap<>();
    params.put("from", Sql.ts(from));
    params.put("to", Sql.ts(to));
    params.put("q", q);
    params.put("pat", "%" + likeEscape(q) + "%");
    return pager.page(jdbc, SEARCH_QUERY, page, params, filters);
  }

  /** LIKE metacharacters taken literally (the default escape character is the backslash). */
  public static String likeEscape(String s) {
    return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  @Override
  public Set<UUID> insertAll(List<ExpenseRow> rows) {
    TenantScope.require();
    Set<UUID> inserted = new HashSet<>();
    for (int from = 0; from < rows.size(); from += INSERT_BATCH) {
      List<ExpenseRow> chunk = rows.subList(from, Math.min(rows.size(), from + INSERT_BATCH));
      StringBuilder sql = new StringBuilder("INSERT INTO ledger.expenses ").append(INSERT_COLS).append(" VALUES ");
      Map<String, Object> p = new HashMap<>();
      for (int i = 0; i < chunk.size(); i++) {
        ExpenseRow r = chunk.get(i);
        if (i > 0) {
          sql.append(", ");
        }
        String n = "_" + i;
        sql.append("(:id").append(n).append(", :a").append(n).append(", :at").append(n).append(", :amt").append(n)
            .append(", :cur").append(n).append(", :mr").append(n).append(", :md").append(n).append(", :mid").append(n)
            .append(", CAST(:cp").append(n).append(" AS public.ltree), :cs").append(n).append(", :rc").append(n)
            .append(", :pm").append(n).append(", :card").append(n).append(", :note").append(n).append(", :tags")
            .append(n).append(", :cref").append(n).append(", :src").append(n).append(", :c").append(n).append(", :u")
            .append(n).append(", :v").append(n).append(", NULL)");
        p.put("id" + n, r.id());
        p.put("a" + n, r.accountId());
        p.put("at" + n, Sql.ts(r.occurredAt()));
        p.put("amt" + n, r.amountPaise());
        p.put("cur" + n, r.currency());
        p.put("mr" + n, r.merchantRaw());
        p.put("md" + n, r.merchantDisplay());
        p.put("mid" + n, r.merchantId());
        p.put("cp" + n, r.categoryPath());
        p.put("cs" + n, r.categorySource());
        p.put("rc" + n, r.resolutionConfidence());
        p.put("pm" + n, r.paymentMethod());
        p.put("card" + n, r.userCardId());
        p.put("note" + n, r.note());
        p.put("tags" + n, r.tags().isEmpty() ? null : Sql.texts(r.tags()));
        p.put("cref" + n, r.clientRef());
        p.put("src" + n, r.source());
        p.put("c" + n, Sql.ts(r.createdAt()));
        p.put("u" + n, Sql.ts(r.updatedAt()));
        p.put("v" + n, r.version());
      }
      sql.append(" ON CONFLICT (account_id, client_ref) WHERE client_ref IS NOT NULL DO NOTHING RETURNING id");
      inserted.addAll(jdbc.sql(sql.toString()).params(p).query((rs, i) -> Rows.uuid(rs, "id")).list());
    }
    return inserted;
  }

  @Override
  public Map<String, UUID> idsByClientRef(Collection<String> refs) {
    TenantScope.require();
    Map<String, UUID> out = new HashMap<>();
    if (refs.isEmpty()) {
      return out;
    }
    jdbc.sql("SELECT id, client_ref FROM ledger.expenses WHERE account_id = (SELECT ledger.app_account())"
            + " AND client_ref = ANY(:refs)")
        .param("refs", Sql.texts(refs))
        .query((rs, i) -> out.put(rs.getString("client_ref"), Rows.uuid(rs, "id")))
        .list();
    return out;
  }

  @Override
  public List<Group> groups(String by, Instant from, Instant to, int limit) {
    TenantScope.require();
    String key = switch (by) {
      case "merchant" -> "coalesce(merchant_id::text, 'text:' || lower(coalesce(merchant_display, '')))";
      case "paymentMethod" -> "payment_method";
      default -> throw new IllegalArgumentException("group by " + by);
    };
    String label = by.equals("merchant") ? "min(coalesce(merchant_display, '(no merchant)'))" : "min(payment_method)";
    return jdbc.sql("SELECT " + key + " AS k, " + label + " AS label, sum(amount_paise) AS total, count(1) AS n"
            + " FROM ledger.expenses WHERE account_id = (SELECT ledger.app_account()) AND deleted_at IS NULL"
            + " AND occurred_at >= :from AND occurred_at < :to GROUP BY 1 ORDER BY total DESC, k LIMIT :limit")
        .param("from", Sql.ts(from)).param("to", Sql.ts(to)).param("limit", limit)
        .query((rs, i) -> new Group(rs.getString("k"), rs.getString("label"), rs.getLong("total"), rs.getInt("n")))
        .list();
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
