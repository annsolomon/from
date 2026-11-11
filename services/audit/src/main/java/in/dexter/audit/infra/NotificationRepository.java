package in.dexter.audit.infra;

import in.dexter.audit.app.NotificationStore;
import in.dexter.audit.domain.NotificationRules.Prefs;
import in.dexter.db.page.KeyType;
import in.dexter.db.page.KeysetPager;
import in.dexter.db.page.KeysetQuery;
import in.dexter.db.page.Page;
import in.dexter.db.page.PageRequest;
import in.dexter.db.sql.Sql;
import in.dexter.db.tenant.TenantScope;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Notifications and preferences. */
public final class NotificationRepository implements NotificationStore {
  static final String COLS = "id, account_id, channel, category, template, payload::text AS payload, status, attempts,"
      + " next_attempt_at, last_error, source_event_id, created_at, sent_at, read_at, version";
  static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
  private final JdbcClient jdbc;
  private final KeysetPager pager;
  private final JsonMapper mapper;
  private final RowMapper<Notification> row;
  public final KeysetQuery<Notification> inbox;
  private final KeysetQuery<Notification> unreadInbox;

  public NotificationRepository(JdbcClient jdbc, KeysetPager pager, JsonMapper mapper) {
    this.jdbc = jdbc;
    this.pager = pager;
    this.mapper = mapper;
    this.row = (rs, n) -> new Notification(rs.getObject("id", UUID.class), rs.getObject("account_id", UUID.class),
        rs.getString("channel"), rs.getString("category"), rs.getString("template"),
        mapper.readValue(rs.getString("payload"), MAP), rs.getString("status"), rs.getInt("attempts"),
        ts(rs, "next_attempt_at"), rs.getString("last_error"), rs.getObject("source_event_id", UUID.class),
        ts(rs, "created_at"), ts(rs, "sent_at"), ts(rs, "read_at"), rs.getInt("version"));
    this.inbox = query("created_desc", "");
    this.unreadInbox = query("created_desc", " AND read_at IS NULL");
  }

  private KeysetQuery<Notification> query(String sort, String extra) {
    return KeysetQuery.<Notification>builder(sort)
        .direction(KeysetQuery.Direction.DESC)
        .key("created_at", KeyType.TIMESTAMPTZ)
        .key("id", KeyType.UUID)
        .sql("SELECT " + COLS + " FROM audit.notifications WHERE account_id = (SELECT audit.app_account())"
            + " AND channel = 'inapp'" + extra)
        .mapper(row)
        .keysOf(x -> List.of(x.createdAt(), x.id()))
        .build();
  }

  static @Nullable Instant ts(ResultSet rs, String col) throws SQLException {
    OffsetDateTime t = rs.getObject(col, OffsetDateTime.class);
    return t == null ? null : t.toInstant();
  }

  @Override
  public boolean insert(Notification n) {
    TenantScope.require();
    return jdbc.sql("INSERT INTO audit.notifications (id, account_id, channel, category, template, payload, status,"
            + " attempts, next_attempt_at, source_event_id, created_at, sent_at) VALUES (:id, :a, :ch, :cat, :t,"
            + " CAST(:p AS jsonb), :st, 0, CAST(:next AS timestamptz), :src, :at, CAST(:sent AS timestamptz))"
            + " ON CONFLICT (source_event_id, channel) DO NOTHING")
        .param("id", n.id()).param("a", n.accountId()).param("ch", n.channel()).param("cat", n.category())
        .param("t", n.template()).param("p", mapper.writeValueAsString(n.payload())).param("st", n.status())
        .param("next", n.nextAttemptAt() == null ? null : Sql.ts(n.nextAttemptAt())).param("src", n.sourceEventId())
        .param("at", Sql.ts(n.createdAt())).param("sent", n.sentAt() == null ? null : Sql.ts(n.sentAt()))
        .update() == 1;
  }

  @Override
  public Page<Notification> inbox(PageRequest page, boolean unreadOnly) {
    TenantScope.require();
    return pager.page(jdbc, unreadOnly ? unreadInbox : inbox, page, Map.of(), Map.of("unread", unreadOnly));
  }

  @Override
  public long unread() {
    TenantScope.require();
    return jdbc.sql("SELECT count(1) FROM audit.notifications WHERE account_id = (SELECT audit.app_account())"
        + " AND channel = 'inapp' AND read_at IS NULL").query(Long.class).single();
  }

  @Override
  public Optional<Notification> find(UUID id) {
    TenantScope.require();
    return jdbc.sql("SELECT " + COLS + " FROM audit.notifications WHERE id = :id").param("id", id).query(row)
        .optional();
  }

  @Override
  public boolean markRead(UUID id, Instant at) {
    TenantScope.require();
    return jdbc.sql("UPDATE audit.notifications SET read_at = :at, version = version + 1 WHERE id = :id"
        + " AND channel = 'inapp' AND read_at IS NULL").param("at", Sql.ts(at)).param("id", id).update() == 1;
  }

  @Override
  public int markAllRead(Instant at) {
    TenantScope.require();
    return jdbc.sql("UPDATE audit.notifications SET read_at = :at, version = version + 1"
        + " WHERE account_id = (SELECT audit.app_account()) AND channel = 'inapp' AND read_at IS NULL")
        .param("at", Sql.ts(at)).update();
  }

  @Override
  public Optional<Prefs> prefs(UUID account) {
    TenantScope.require();
    return jdbc.sql("SELECT email, push, muted, version FROM audit.notification_prefs WHERE account_id = :a")
        .param("a", account)
        .query((rs, i) -> new Prefs(rs.getBoolean("email"), rs.getBoolean("push"), texts(rs.getArray("muted")),
            rs.getInt("version")))
        .optional();
  }

  static Set<String> texts(Array a) throws SQLException {
    return a == null ? Set.of() : Set.of((String[]) a.getArray());
  }

  @Override
  public boolean savePrefs(UUID account, Prefs p, int expectedVersion, Instant at) {
    TenantScope.require();
    if (expectedVersion == 0) {
      try {
        return jdbc.sql("INSERT INTO audit.notification_prefs (account_id, email, push, muted, version, updated_at)"
                + " VALUES (:a, :e, :p, :m, 1, :at)")
            .param("a", account).param("e", p.email()).param("p", p.push()).param("m", Sql.texts(p.muted()))
            .param("at", Sql.ts(at)).update() == 1;
      } catch (DuplicateKeyException e) {
        return false;
      }
    }
    return jdbc.sql("UPDATE audit.notification_prefs SET email = :e, push = :p, muted = :m, version = version + 1,"
            + " updated_at = :at WHERE account_id = :a AND version = :v")
        .param("a", account).param("e", p.email()).param("p", p.push()).param("m", Sql.texts(p.muted()))
        .param("at", Sql.ts(at)).param("v", expectedVersion).update() == 1;
  }

  @Override
  public List<Notification> claimDue(Instant now, int limit, Instant leaseUntil) {
    TenantScope.require();
    List<Notification> due = jdbc.sql("SELECT " + COLS + " FROM audit.notifications WHERE status = 'pending'"
            + " AND next_attempt_at <= :now ORDER BY next_attempt_at LIMIT :n FOR UPDATE SKIP LOCKED")
        .param("now", Sql.ts(now)).param("n", limit).query(row).list();
    if (!due.isEmpty()) {
      jdbc.sql("UPDATE audit.notifications SET next_attempt_at = :lease WHERE id = ANY (:ids)")
          .param("lease", Sql.ts(leaseUntil)).param("ids", Sql.uuids(due.stream().map(Notification::id).toList()))
          .update();
    }
    return due;
  }

  @Override
  public void delivered(UUID id, int attempts, Instant at) {
    TenantScope.require();
    jdbc.sql("UPDATE audit.notifications SET status = 'sent', attempts = :n, next_attempt_at = NULL, sent_at = :at,"
            + " last_error = NULL, version = version + 1 WHERE id = :id")
        .param("n", attempts).param("at", Sql.ts(at)).param("id", id).update();
  }

  @Override
  public void attemptFailed(UUID id, int attempts, String error, @Nullable Instant nextAttemptAt) {
    TenantScope.require();
    jdbc.sql("UPDATE audit.notifications SET status = :st, attempts = :n, next_attempt_at = CAST(:next AS timestamptz),"
            + " last_error = :e, version = version + 1 WHERE id = :id")
        .param("st", nextAttemptAt == null ? "failed" : "pending").param("n", attempts)
        .param("next", nextAttemptAt == null ? null : Sql.ts(nextAttemptAt)).param("e", error).param("id", id)
        .update();
  }
}
