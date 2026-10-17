package in.dexter.identity.infra;

import in.dexter.core.error.DexterException;
import in.dexter.core.error.FieldError;
import in.dexter.core.error.ForbiddenException;
import in.dexter.core.error.NotFoundException;
import in.dexter.core.error.ValidationException;
import in.dexter.core.error.VersionConflictException;
import in.dexter.db.page.KeyType;
import in.dexter.db.page.KeysetPager;
import in.dexter.db.page.KeysetQuery;
import in.dexter.db.page.Page;
import in.dexter.db.page.PageRequest;
import in.dexter.db.sql.Rows;
import in.dexter.db.sql.Sql;
import in.dexter.db.tenant.TenantScope;
import in.dexter.identity.app.AdminDirectory;
import in.dexter.identity.domain.IdentityErrors;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Admin endpoints acting on others, as identity_admin (tenant = calling admin, actor role chosen by the use-case).
 * Reads go through the {@code *_admin_read} policies; admin writes only through the audited definer functions
 * admin_create / admin_update, whose DX SQLSTATEs map to API errors here. Indexes: admins_list (keyset
 * created_at DESC, id DESC), admin_roles pkey, admin_mfa_totp pkey, accounts_email_key, mfa_totp pkey.
 */
public final class AdminDirectoryRepository implements AdminDirectory {
  static final String VIEW_COLS = "a.id, a.email, a.display_name, a.status, a.break_glass_eligible, a.created_at,"
      + " a.version, EXISTS (SELECT 1 FROM identity.admin_mfa_totp m WHERE m.admin_id = a.id"
      + " AND m.confirmed_at IS NOT NULL) AS mfa_confirmed,"
      + " ARRAY(SELECT r.role FROM identity.admin_roles r WHERE r.admin_id = a.id) AS roles";
  static final RowMapper<AdminView> VIEW = (rs, i) -> new AdminView(Rows.uuid(rs, "id"), rs.getString("email"),
      rs.getString("display_name"), rs.getString("status"), rs.getBoolean("break_glass_eligible"),
      rs.getBoolean("mfa_confirmed"), AdminRepository.texts(rs.getArray("roles")).stream().sorted().toList(), Rows.instant(rs, "created_at"),
      rs.getLong("version"));
  /** GET /v1/admin/admins, exposed for PlanAssert. */
  public static final KeysetQuery<AdminView> LIST = KeysetQuery.<AdminView>builder("created_desc")
      .key("created_at", KeyType.TIMESTAMPTZ)
      .key("id", KeyType.UUID)
      .sql("SELECT " + VIEW_COLS + " FROM identity.admins a WHERE true")
      .mapper(VIEW)
      .keysOf(a -> List.of(a.createdAt(), a.id()))
      .build();

  private final JdbcClient jdbc;
  private final KeysetPager pager;

  public AdminDirectoryRepository(JdbcClient jdbc, KeysetPager pager) {
    this.jdbc = jdbc;
    this.pager = pager;
  }

  @Override
  public Page<AdminView> list(PageRequest page) {
    TenantScope.require();
    return pager.page(jdbc, LIST, page, Map.of(), Map.of());
  }

  @Override
  public Optional<AdminView> find(UUID id) {
    TenantScope.require();
    return jdbc.sql("SELECT " + VIEW_COLS + " FROM identity.admins a WHERE a.id = :id")
        .param("id", id).query(VIEW).optional();
  }

  @Override
  public Created create(UUID id, String email, String displayName, List<String> roles, boolean breakGlass,
      UUID inviteId, byte[] inviteHash, Instant inviteExpires) {
    TenantScope.require();
    return mapped(() -> jdbc.sql("SELECT admin_id, version, created_at FROM identity.admin_create(:id,"
            + " CAST(:e AS citext), :dn, :r, :bg, :iid, :ih, :ix)")
        .param("id", id).param("e", email).param("dn", displayName).param("r", Sql.texts(roles))
        .param("bg", breakGlass).param("iid", inviteId).param("ih", inviteHash).param("ix", Sql.ts(inviteExpires))
        .query((rs, i) -> new Created(Rows.uuid(rs, "admin_id"), rs.getLong("version"),
            Rows.instant(rs, "created_at")))
        .single());
  }

  @Override
  public Updated update(UUID id, long expectedVersion, String displayName, String status, List<String> roles,
      Boolean breakGlass, Boolean resetMfa) {
    TenantScope.require();
    return mapped(() -> jdbc.sql("SELECT version, sessions_revoked FROM identity.admin_update(:id, :v, :dn, :st,"
            + " CAST(:r AS text[]), :bg, :rm)")
        .param("id", id).param("v", (int) expectedVersion).param("dn", displayName).param("st", status)
        .param("r", roles == null ? null : Sql.texts(roles)).param("bg", breakGlass).param("rm", resetMfa)
        .query((rs, i) -> new Updated(rs.getLong("version"), rs.getInt("sessions_revoked")))
        .single(), expectedVersion);
  }

  @Override
  public Optional<AccountSummary> findAccount(String email) {
    TenantScope.require();
    return jdbc.sql("SELECT a.id, a.email, a.status, a.email_verified_at, a.tier_cache, a.created_at,"
            + " EXISTS (SELECT 1 FROM identity.mfa_totp m WHERE m.account_id = a.id AND m.confirmed_at IS NOT NULL)"
            + " AS mfa FROM identity.accounts a WHERE a.email = CAST(:e AS citext)")
        .param("e", email)
        .query((rs, i) -> new AccountSummary(Rows.uuid(rs, "id"), rs.getString("email"), rs.getString("status"),
            rs.getTimestamp("email_verified_at") != null, rs.getBoolean("mfa"), rs.getString("tier_cache"),
            Rows.instant(rs, "created_at")))
        .optional();
  }

  @Override
  public void audit(UUID adminId, String action, String targetType, UUID targetId, String detailsJson) {
    TenantScope.require();
    AdminRepository.insertAudit(jdbc, adminId, action, targetType, targetId, detailsJson);
  }

  /** DX SQLSTATEs raised by admin_create / admin_update → API errors. */
  static <T> T mapped(Supplier<T> call) {
    return mapped(call, -1);
  }

  static <T> T mapped(Supplier<T> call, long expectedVersion) {
    try {
      return call.get();
    } catch (DataAccessException e) {
      String state = sqlState(e);
      if (state == null) {
        throw e;
      }
      throw switch (state) {
        case "DX403" -> e.getMessage() != null && e.getMessage().contains("themselves")
            ? new DexterException(IdentityErrors.SELF_CHANGE_FORBIDDEN, "Admins cannot change themselves")
            : new ForbiddenException("SUPER_ADMIN (break-glass) required");
        case "DX404" -> new NotFoundException("admin");
        case "DX409" -> new DexterException(IdentityErrors.ADMIN_EMAIL_TAKEN, "An admin with this email already exists");
        case "DX412" -> new VersionConflictException("admin", expectedVersion);
        case "DX400" -> new ValidationException(List.of(new FieldError("roles", "INVALID",
            "Unknown or non-standing role, or invalid status")));
        default -> e;
      };
    }
  }

  private static String sqlState(Throwable e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof SQLException s && s.getSQLState() != null && s.getSQLState().startsWith("DX")) {
        return s.getSQLState();
      }
    }
    return null;
  }
}
