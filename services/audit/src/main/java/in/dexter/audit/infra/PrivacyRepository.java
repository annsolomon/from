package in.dexter.audit.infra;

import in.dexter.audit.app.PrivacyStore;
import in.dexter.db.page.KeyType;
import in.dexter.db.page.KeysetPager;
import in.dexter.db.page.KeysetQuery;
import in.dexter.db.page.Page;
import in.dexter.db.page.PageRequest;
import in.dexter.db.sql.Sql;
import in.dexter.db.tenant.TenantScope;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The privacy saga's requests, and audit's own privacy functions. */
public final class PrivacyRepository implements PrivacyStore {
  static final String COLS = "id, account_id, subject_ref, kind, status, phase, attempt, services, steps::text AS steps,"
      + " phase_started_at, next_check_at, archive_key, archive_bytes, error, created_at, completed_at, version";
  private final JdbcClient jdbc;
  private final KeysetPager pager;
  private final RowMapper<Request> row = PrivacyRepository::row;
  private final KeysetQuery<Request> list;

  public PrivacyRepository(JdbcClient jdbc, KeysetPager pager) {
    this.jdbc = jdbc;
    this.pager = pager;
    this.list = KeysetQuery.<Request>builder("created_desc")
        .direction(KeysetQuery.Direction.DESC)
        .key("created_at", KeyType.TIMESTAMPTZ)
        .key("id", KeyType.UUID)
        .sql("SELECT " + COLS + " FROM audit.privacy_requests WHERE account_id = (SELECT audit.app_account())")
        .mapper(row)
        .keysOf(r -> List.of(r.createdAt(), r.id()))
        .build();
  }

  static Request row(ResultSet rs, int n) throws SQLException {
    long bytes = rs.getLong("archive_bytes");
    Long archiveBytes = rs.wasNull() ? null : bytes;
    return new Request(rs.getObject("id", UUID.class), rs.getObject("account_id", UUID.class),
        rs.getString("subject_ref"), rs.getString("kind"), rs.getString("status"), rs.getString("phase"),
        rs.getInt("attempt"), List.of((String[]) rs.getArray("services").getArray()), rs.getString("steps"),
        NotificationRepository.ts(rs, "phase_started_at"), NotificationRepository.ts(rs, "next_check_at"),
        rs.getString("archive_key"), archiveBytes, rs.getString("error"), NotificationRepository.ts(rs, "created_at"),
        NotificationRepository.ts(rs, "completed_at"), rs.getInt("version"));
  }

  @Override
  public boolean insert(Request r) {
    TenantScope.require();
    try {
      jdbc.sql("INSERT INTO audit.privacy_requests (id, account_id, kind, status, phase, attempt, services, steps,"
              + " phase_started_at, next_check_at, created_at, version) VALUES (:id, :a, :k, :st, :ph, :at, :svc,"
              + " CAST(:steps AS jsonb), :ps, CAST(:next AS timestamptz), :c, 0)")
          .param("id", r.id()).param("a", r.accountId()).param("k", r.kind()).param("st", r.status())
          .param("ph", r.phase()).param("at", r.attempt()).param("svc", Sql.texts(r.services()))
          .param("steps", r.stepsJson()).param("ps", Sql.ts(r.phaseStartedAt()))
          .param("next", r.nextCheckAt() == null ? null : Sql.ts(r.nextCheckAt())).param("c", Sql.ts(r.createdAt()))
          .update();
      return true;
    } catch (DuplicateKeyException e) {
      return false;
    }
  }

  @Override
  public Optional<Request> find(UUID id) {
    TenantScope.require();
    return jdbc.sql("SELECT " + COLS + " FROM audit.privacy_requests WHERE id = :id").param("id", id).query(row)
        .optional();
  }

  @Override
  public Optional<Request> lock(UUID id) {
    TenantScope.require();
    return jdbc.sql("SELECT " + COLS + " FROM audit.privacy_requests WHERE id = :id FOR UPDATE").param("id", id)
        .query(row).optional();
  }

  @Override
  public Page<Request> list(PageRequest page) {
    TenantScope.require();
    return pager.page(jdbc, list, page, Map.of(), Map.of());
  }

  @Override
  public List<UUID> due(Instant now, int limit) {
    TenantScope.require();
    return jdbc.sql("SELECT id FROM audit.privacy_requests WHERE status = 'running' AND next_check_at <= :now"
            + " ORDER BY next_check_at LIMIT :n FOR UPDATE SKIP LOCKED")
        .param("now", Sql.ts(now)).param("n", limit).query(UUID.class).list();
  }

  @Override
  public void update(Request r) {
    TenantScope.require();
    int n = jdbc.sql("UPDATE audit.privacy_requests SET status = :st, phase = :ph, attempt = :at,"
            + " steps = CAST(:steps AS jsonb), phase_started_at = :ps, next_check_at = CAST(:next AS timestamptz),"
            + " archive_key = :ak, archive_bytes = CAST(:ab AS bigint), error = :e,"
            + " completed_at = CAST(:done AS timestamptz), version = version + 1 WHERE id = :id AND version = :v")
        .param("st", r.status()).param("ph", r.phase()).param("at", r.attempt()).param("steps", r.stepsJson())
        .param("ps", Sql.ts(r.phaseStartedAt())).param("next", r.nextCheckAt() == null ? null : Sql.ts(r.nextCheckAt()))
        .param("ak", r.archiveKey()).param("ab", r.archiveBytes()).param("e", r.error())
        .param("done", r.completedAt() == null ? null : Sql.ts(r.completedAt())).param("id", r.id())
        .param("v", r.version()).update();
    if (n != 1) {
      throw new IllegalStateException("privacy request " + r.id() + " changed under its lock");
    }
  }

  @Override
  public int pseudonymize(UUID account, String subjectRef) {
    TenantScope.require();
    return jdbc.sql("UPDATE audit.privacy_requests SET account_id = NULL, subject_ref = :s, version = version + 1"
        + " WHERE account_id = :a").param("s", subjectRef).param("a", account).update();
  }

  @Override
  public long eraseLocal(UUID account) {
    TenantScope.require();
    return jdbc.sql("SELECT audit.privacy_erase(:a)").param("a", account).query(Long.class).single();
  }

  @Override
  public long remainingLocal(UUID account) {
    TenantScope.require();
    return jdbc.sql("SELECT audit.privacy_remaining(:a)").param("a", account).query(Long.class).single();
  }

  @Override
  public String exportLocal(UUID account) {
    TenantScope.require();
    return jdbc.sql("SELECT CAST(audit.privacy_export(:a) AS text)").param("a", account).query(String.class).single();
  }
}
