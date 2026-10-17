package in.dexter.identity.infra;

import in.dexter.db.page.KeyType;
import in.dexter.db.page.KeysetPager;
import in.dexter.db.page.KeysetQuery;
import in.dexter.db.page.Page;
import in.dexter.db.page.PageRequest;
import in.dexter.db.sql.Rows;
import in.dexter.db.sql.Sql;
import in.dexter.db.tenant.TenantScope;
import in.dexter.identity.app.SessionStore;
import in.dexter.identity.domain.RevokeReason;
import java.sql.Array;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@code identity.sessions} under RLS. The list is keyset-paged on {@code (created_at DESC, id DESC)} over open
 * sessions: index {@code sessions_active_list (account_id, created_at DESC, id DESC) WHERE revoked_at IS NULL}.
 */
public final class SessionRepository implements SessionStore {
  static final String VIEW_COLS = "id, client_id, device_label, host(ip_first) AS ip, created_at, last_seen_at";
  static final RowMapper<SessionView> VIEW = (rs, i) -> new SessionView(Rows.uuid(rs, "id"), rs.getString("client_id"),
      rs.getString("device_label"), rs.getString("ip"), Rows.instant(rs, "created_at"),
      Rows.instant(rs, "last_seen_at"));
  /** The list query, exposed for PlanAssert. */
  public static final KeysetQuery<SessionView> LIST = KeysetQuery.<SessionView>builder("created_desc")
      .key("created_at", KeyType.TIMESTAMPTZ)
      .key("id", KeyType.UUID)
      .sql("SELECT " + VIEW_COLS + " FROM identity.sessions"
          + " WHERE account_id = (SELECT identity.app_account()) AND revoked_at IS NULL")
      .mapper(VIEW)
      .keysOf(s -> List.of(s.createdAt(), s.id()))
      .build();

  private final JdbcClient jdbc;
  private final KeysetPager pager;

  public SessionRepository(JdbcClient jdbc, KeysetPager pager) {
    this.jdbc = jdbc;
    this.pager = pager;
  }

  @Override
  public void create(NewSession s) {
    TenantScope.require();
    jdbc.sql("INSERT INTO identity.sessions (id, account_id, client_id, device_label, ua_hash, ip_first, amr,"
            + " auth_time, created_at, last_seen_at) VALUES (:id, :a, :c, :dl, :ua, CAST(:ip AS inet), :amr, :at,"
            + " :now, :now)")
        .param("id", s.id()).param("a", s.accountId()).param("c", s.clientId()).param("dl", s.deviceLabel())
        .param("ua", s.uaHash()).param("ip", s.ip()).param("amr", Sql.texts(s.amr()))
        .param("at", Sql.ts(s.authTime())).param("now", Sql.ts(s.now()))
        .update();
  }

  @Override
  public Optional<SessionRow> find(UUID accountId, UUID sessionId) {
    TenantScope.require();
    return jdbc.sql("SELECT id, account_id, client_id, amr, auth_time, revoked_at FROM identity.sessions"
            + " WHERE account_id = :a AND id = :id")
        .param("a", accountId).param("id", sessionId)
        .query((rs, i) -> new SessionRow(Rows.uuid(rs, "id"), Rows.uuid(rs, "account_id"), rs.getString("client_id"),
            texts(rs.getArray("amr")), Rows.instant(rs, "auth_time"), Rows.instant(rs, "revoked_at")))
        .optional();
  }

  @Override
  public Page<SessionView> listActive(PageRequest page) {
    TenantScope.require();
    return pager.page(jdbc, LIST, page, Map.of(), Map.of());
  }

  @Override
  public boolean revoke(UUID accountId, UUID sessionId, RevokeReason reason, Instant now) {
    TenantScope.require();
    return jdbc.sql("UPDATE identity.sessions SET revoked_at = :now, revoke_reason = :r, version = version + 1"
            + " WHERE account_id = :a AND id = :id AND revoked_at IS NULL")
        .param("now", Sql.ts(now)).param("r", reason.db()).param("a", accountId).param("id", sessionId)
        .update() == 1;
  }

  @Override
  public List<UUID> revokeAll(UUID accountId, UUID keep, RevokeReason reason, Instant now) {
    TenantScope.require();
    return jdbc.sql("UPDATE identity.sessions SET revoked_at = :now, revoke_reason = :r, version = version + 1"
            + " WHERE account_id = :a AND revoked_at IS NULL AND id IS DISTINCT FROM CAST(:keep AS uuid) RETURNING id")
        .param("now", Sql.ts(now)).param("r", reason.db()).param("a", accountId).param("keep", keep)
        .query((rs, i) -> Rows.uuid(rs, "id"))
        .list();
  }

  @Override
  public void touch(UUID accountId, UUID sessionId, Instant now) {
    TenantScope.require();
    jdbc.sql("UPDATE identity.sessions SET last_seen_at = :now WHERE account_id = :a AND id = :id")
        .param("now", Sql.ts(now)).param("a", accountId).param("id", sessionId).update();
  }

  static List<String> texts(Array a) throws java.sql.SQLException {
    return a == null ? List.of() : Arrays.stream((Object[]) a.getArray()).map(Object::toString).toList();
  }
}
