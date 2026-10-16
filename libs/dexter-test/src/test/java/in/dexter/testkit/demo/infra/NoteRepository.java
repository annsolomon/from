package in.dexter.testkit.demo.infra;

import in.dexter.db.page.KeyType;
import in.dexter.db.page.KeysetPager;
import in.dexter.db.page.KeysetQuery;
import in.dexter.db.page.Page;
import in.dexter.db.page.PageRequest;
import in.dexter.db.sql.OptimisticUpdate;
import in.dexter.db.sql.Rows;
import in.dexter.db.sql.Sql;
import in.dexter.db.tenant.TenantScope;
import in.dexter.testkit.demo.domain.Note;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * JDBC repository (infra). Every method first calls {@link TenantScope#require()}: it only runs inside
 * TenantTx, so RLS always applies. The tenant predicate is also explicit (helps the planner).
 */
public final class NoteRepository {
  static final String COLS = "account_id, id, title, kind, amount_paise, created_at, version";
  static final RowMapper<Note> MAPPER = (rs, i) -> new Note(Rows.uuid(rs, "account_id"), Rows.uuid(rs, "id"),
      rs.getString("title"), rs.getString("kind"), rs.getLong("amount_paise"), Rows.instant(rs, "created_at"),
      rs.getLong("version"));
  /** The list query, exposed for PlanAssert. */
  public static final KeysetQuery<Note> LIST = list(false);
  static final KeysetQuery<Note> LIST_BY_KIND = list(true);

  private final JdbcClient jdbc;
  private final KeysetPager pager;

  public NoteRepository(JdbcClient jdbc, KeysetPager pager) {
    this.jdbc = jdbc;
    this.pager = pager;
  }

  private static KeysetQuery<Note> list(boolean byKind) {
    return KeysetQuery.<Note>builder("created_desc")
        .key("created_at", KeyType.TIMESTAMPTZ)
        .key("id", KeyType.UUID)
        .sql("SELECT " + COLS + " FROM demo.notes WHERE account_id = (SELECT demo.app_account())"
            + (byKind ? " AND kind = :kind" : ""))
        .mapper(MAPPER)
        .keysOf(n -> List.of(n.createdAt(), n.id()))
        .build();
  }

  public Note insert(UUID accountId, UUID id, String title, String kind, long amountPaise, Instant createdAt) {
    TenantScope.require();
    return jdbc.sql("INSERT INTO demo.notes (" + COLS + ") VALUES (:a, :id, :t, :k, :amt, :at, 1) RETURNING " + COLS)
        .param("a", accountId).param("id", id).param("t", title).param("k", kind).param("amt", amountPaise)
        .param("at", Sql.ts(createdAt))
        .query(MAPPER).single();
  }

  /** Versioned rename (MASTER_PLAN 3.4): 412 on a stale version, 404 when invisible. Returns the new version. */
  public long rename(UUID accountId, UUID id, long expectedVersion, String title) {
    TenantScope.require();
    List<Long> v = jdbc.sql("UPDATE demo.notes SET title = :t, version = version + 1"
            + " WHERE account_id = :a AND id = :id AND version = :v RETURNING version")
        .param("t", title).param("a", accountId).param("id", id).param("v", expectedVersion)
        .query(Long.class).list();
    OptimisticUpdate.check(v.size(), () -> jdbc.sql("SELECT count(1) FROM demo.notes WHERE account_id = :a AND id = :id")
        .param("a", accountId).param("id", id).query(Long.class).single() > 0, "note", expectedVersion);
    return v.getFirst();
  }

  /** Detail by primary key (account_id, id) -> notes_pkey. Empty when missing or invisible under RLS. */
  public Optional<Note> find(UUID accountId, UUID id) {
    TenantScope.require();
    return jdbc.sql("SELECT " + COLS + " FROM demo.notes WHERE account_id = :a AND id = :id")
        .param("a", accountId).param("id", id).query(MAPPER).optional();
  }

  public Page<Note> list(PageRequest req, String kind) {
    TenantScope.require();
    if (kind == null) {
      return pager.page(jdbc, LIST, req, Map.of(), Map.of());
    }
    return pager.page(jdbc, LIST_BY_KIND, req, Map.of("kind", kind), Map.of("kind", kind));
  }
}
