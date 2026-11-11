package in.dexter.audit.infra;

import in.dexter.audit.app.AuditStore;
import in.dexter.audit.domain.ChainVerifier;
import in.dexter.db.page.KeyType;
import in.dexter.db.page.KeysetPager;
import in.dexter.db.page.KeysetQuery;
import in.dexter.db.page.Page;
import in.dexter.db.page.PageRequest;
import in.dexter.db.sql.Sql;
import in.dexter.db.tenant.TenantScope;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/** The audit log on one pool: the app pool appends; the admin pool (operators) reads and verifies. */
public final class AuditRepository implements AuditStore {
  static final String COLS = "seq, occurred_at, id, actor_type, actor_id, action, target_type, target_id,"
      + " request_id, details::text AS details, hash";
  private final JdbcClient jdbc;
  private final KeysetPager pager;
  private final JsonMapper mapper;
  private final RowMapper<Row> row = AuditRepository::row;

  public AuditRepository(JdbcClient jdbc, KeysetPager pager, JsonMapper mapper) {
    this.jdbc = jdbc;
    this.pager = pager;
    this.mapper = mapper;
  }

  static Row row(ResultSet rs, int n) throws SQLException {
    return new Row(rs.getLong("seq"), rs.getObject("occurred_at", java.time.OffsetDateTime.class).toInstant(),
        rs.getObject("id", UUID.class), rs.getString("actor_type"), rs.getString("actor_id"), rs.getString("action"),
        rs.getString("target_type"), rs.getString("target_id"), rs.getString("request_id"), rs.getString("details"),
        rs.getBytes("hash"));
  }

  @Override
  public long append(Entry e) {
    TenantScope.require();
    return jdbc.sql("SELECT audit.append(CAST(:at AS text), CAST(:aid AS text), CAST(:act AS text),"
            + " CAST(:tt AS text), CAST(:tid AS text), CAST(:ip AS bytea), CAST(:rid AS text), CAST(:d AS jsonb))")
        .param("at", e.actorType()).param("aid", e.actorId()).param("act", e.action()).param("tt", e.targetType())
        .param("tid", e.targetId()).param("ip", e.ipHash()).param("rid", e.requestId())
        .param("d", mapper.writeValueAsString(e.details())).query(Long.class).single();
  }

  /** The keyset query for this combination of filters (newest first by seq). */
  KeysetQuery<Row> query(Filter f) {
    StringBuilder where = new StringBuilder(" WHERE true");
    if (f.actorId() != null) {
      where.append(" AND actor_id = :actor");
    }
    if (f.action() != null) {
      where.append(" AND action = :action");
    }
    if (f.targetType() != null) {
      where.append(" AND target_type = :ttype");
    }
    if (f.targetId() != null) {
      where.append(" AND target_id = :tid");
    }
    if (f.from() != null) {
      where.append(" AND occurred_at >= :from");
    }
    if (f.to() != null) {
      where.append(" AND occurred_at < :to");
    }
    return KeysetQuery.<Row>builder("seq_desc")
        .direction(KeysetQuery.Direction.DESC)
        .key("seq", KeyType.BIGINT)
        .sql("SELECT " + COLS + " FROM audit.audit_log" + where)
        .mapper(row)
        .keysOf(r -> List.of(r.seq()))
        .build();
  }

  @Override
  public Page<Row> page(Filter f, PageRequest page) {
    TenantScope.require();
    Map<String, Object> params = new LinkedHashMap<>();
    Map<String, Object> filters = new LinkedHashMap<>();
    if (f.actorId() != null) {
      params.put("actor", f.actorId());
    }
    if (f.action() != null) {
      params.put("action", f.action());
    }
    if (f.targetType() != null) {
      params.put("ttype", f.targetType());
    }
    if (f.targetId() != null) {
      params.put("tid", f.targetId());
    }
    filters.putAll(params);
    if (f.from() != null) {
      params.put("from", Sql.ts(f.from()));
      filters.put("from", f.from().toString());
    }
    if (f.to() != null) {
      params.put("to", Sql.ts(f.to()));
      filters.put("to", f.to().toString());
    }
    return pager.page(jdbc, query(f), page, params, filters);
  }

  @Override
  public List<ChainVerifier.Row> chain(long fromSeq, int limit) {
    TenantScope.require();
    return jdbc.sql("SELECT seq, prev_hash, hash, audit.chain_hash(prev_hash, seq, occurred_at, id, actor_type,"
            + " actor_id, action, target_type, target_id, ip_hash, request_id, details) AS recomputed"
            + " FROM audit.audit_log WHERE seq >= :from ORDER BY seq LIMIT :n")
        .param("from", fromSeq).param("n", limit)
        .query((rs, i) -> new ChainVerifier.Row(rs.getLong("seq"), rs.getBytes("prev_hash"), rs.getBytes("hash"),
            rs.getBytes("recomputed")))
        .list();
  }

  @Override
  public Head head() {
    TenantScope.require();
    return jdbc.sql("SELECT seq, hash FROM audit.chain_head_now()")
        .query((rs, i) -> new Head(rs.getLong("seq"), rs.getBytes("hash"))).single();
  }

  @Override
  public Map<Long, byte[]> anchors() {
    TenantScope.require();
    Map<Long, byte[]> out = new LinkedHashMap<>();
    jdbc.sql("SELECT seq, hash FROM audit.audit_anchors ORDER BY seq")
        .query((rs, i) -> Map.entry(rs.getLong("seq"), rs.getBytes("hash"))).list()
        .forEach(e -> out.put(e.getKey(), e.getValue()));
    return out;
  }

  @Override
  public long lastAnchorSeq() {
    TenantScope.require();
    return jdbc.sql("SELECT coalesce(max(seq), 0) FROM audit.audit_anchors").query(Long.class).single();
  }

  @Override
  public void insertAnchor(long seq, byte[] hash, String objectKey) {
    TenantScope.require();
    jdbc.sql("INSERT INTO audit.audit_anchors (seq, hash, object_key) VALUES (:s, :h, :k) ON CONFLICT (seq) DO NOTHING")
        .param("s", seq).param("h", hash).param("k", objectKey).update();
  }

  /** For tests and tooling: every seq present (ordered). */
  public List<Long> seqs() {
    return new ArrayList<>(jdbc.sql("SELECT seq FROM audit.audit_log ORDER BY seq").query(Long.class).list());
  }
}
