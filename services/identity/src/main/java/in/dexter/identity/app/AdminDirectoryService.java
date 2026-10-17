package in.dexter.identity.app;

import in.dexter.contracts.events.identity.AdminActionV1;
import in.dexter.core.error.FieldError;
import in.dexter.core.error.ForbiddenException;
import in.dexter.core.error.NotFoundException;
import in.dexter.core.error.ValidationException;
import in.dexter.core.id.Ids;
import in.dexter.core.tenant.Tenant;
import in.dexter.db.page.Page;
import in.dexter.db.page.PageRequest;
import in.dexter.db.tenant.TenantTx;
import in.dexter.events.OutboxWriter;
import in.dexter.identity.app.AdminDirectory.AccountSummary;
import in.dexter.identity.app.AdminDirectory.AdminView;
import in.dexter.identity.domain.AdminPermissions;
import in.dexter.identity.domain.Emails;
import in.dexter.identity.domain.SecretTokens;
import in.dexter.security.AuthContext;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Admin endpoints acting on others (MASTER_PLAN P05). Runs on the identity_admin pool with the calling admin as
 * tenant and the role the use-case needs as actor role, so the database policies apply on top of the
 * {@code @RequiresPermission} check (defense in depth). Admin management needs SUPER_ADMIN, which only an active
 * break-glass elevation grants; every action is audited (admin_audit row + identity.admin.action event).
 */
public final class AdminDirectoryService {
  private static final Logger log = LoggerFactory.getLogger(AdminDirectoryService.class);
  static final Set<String> STANDING_ROLES = Set.of(Tenant.SUPPORT, Tenant.CATALOG_EDITOR, Tenant.FINANCE);

  public record NewAdmin(String email, String displayName, List<String> roles, boolean breakGlassEligible) {}

  /** Null fields are unchanged. */
  public record AdminChange(String displayName, String status, List<String> roles, Boolean breakGlassEligible,
      Boolean resetMfa) {}

  private final TenantTx adminTx;
  private final AdminDirectory directory;
  private final OutboxWriter adminOutbox;
  private final Mailer mailer;
  private final ElevationService elevations;
  private final SecuritySettings security;
  private final Clock clock;

  public AdminDirectoryService(TenantTx adminTx, AdminDirectory directory, OutboxWriter adminOutbox, Mailer mailer,
      ElevationService elevations, SecuritySettings security, Clock clock) {
    this.adminTx = adminTx;
    this.directory = directory;
    this.adminOutbox = adminOutbox;
    this.mailer = mailer;
    this.elevations = elevations;
    this.security = security;
    this.clock = clock;
  }

  public Page<AdminView> list(AuthContext caller, PageRequest page) {
    Tenant t = superAdmin(caller);
    return adminTx.read(t, () -> directory.list(page));
  }

  public AdminView get(AuthContext caller, UUID id) {
    Tenant t = superAdmin(caller);
    return adminTx.read(t, () -> directory.find(id)).orElseThrow(() -> new NotFoundException("admin"));
  }

  /** Creates an invited admin and emails a single-use link (after commit). */
  public AdminView create(AuthContext caller, NewAdmin cmd) {
    Tenant t = superAdmin(caller);
    List<FieldError> errors = new ArrayList<>();
    String email = Emails.normalize(cmd.email());
    if (email == null) {
      errors.add(new FieldError("email", "INVALID", "A valid email address is required"));
    }
    String name = displayName(cmd.displayName(), errors);
    List<String> roles = roles(cmd.roles(), errors);
    if (!errors.isEmpty()) {
      throw new ValidationException(errors);
    }
    UUID id = Ids.newId();
    String token = SecretTokens.newToken();
    Instant now = clock.instant();
    Instant inviteExpires = now.plus(security.inviteTtl());
    return adminTx.write(t, () -> {
      directory.create(id, email, name, roles, cmd.breakGlassEligible(), Ids.newId(), SecretTokens.hash(token),
          inviteExpires);
      event(caller.accountId(), "ADMIN_CREATED", "admin", id, "", null, now);
      mailer.sendAfterCommit(IdentityMails.adminInvite(email, IdentityMails.link(security.inviteLink(), token),
          security.inviteTtl().toHours()));
      log.info("ADMIN_CREATED by={} admin={} roles={}", caller.accountId(), id, roles);
      return directory.find(id).orElseThrow();
    });
  }

  public AdminView update(AuthContext caller, UUID id, long expectedVersion, AdminChange change) {
    Tenant t = superAdmin(caller);
    if (id.equals(caller.accountId())) {
      throw new in.dexter.core.error.DexterException(in.dexter.identity.domain.IdentityErrors.SELF_CHANGE_FORBIDDEN,
          "Ask another super admin to change your own admin record");
    }
    List<FieldError> errors = new ArrayList<>();
    String name = change.displayName() == null ? null : displayNameOrEmpty(change.displayName(), errors);
    if (change.status() != null && !Set.of("active", "disabled").contains(change.status())) {
      errors.add(new FieldError("status", "INVALID", "status is active or disabled"));
    }
    List<String> roles = change.roles() == null ? null : roles(change.roles(), errors);
    if (!errors.isEmpty()) {
      throw new ValidationException(errors);
    }
    Instant now = clock.instant();
    return adminTx.write(t, () -> {
      AdminDirectory.Updated u = directory.update(id, expectedVersion, name, change.status(), roles,
          change.breakGlassEligible(), change.resetMfa());
      event(caller.accountId(), "ADMIN_UPDATED", "admin", id, "", null, now);
      log.info("ADMIN_UPDATED by={} admin={} sessionsRevoked={}", caller.accountId(), id, u.sessionsRevoked());
      return directory.find(id).orElseThrow();
    });
  }

  /** Support lookup by email (audited even when nothing is found). */
  public Optional<AccountSummary> lookupAccount(AuthContext caller, String rawEmail) {
    AdminGuard.require(caller);
    String role = AdminPermissions.ACCOUNT_READER_ROLES.stream().filter(caller::hasRole).findFirst()
        .orElseThrow(() -> new ForbiddenException("No role may read accounts"));
    if (Tenant.SUPER_ADMIN.equals(role)) {
      elevations.requireActive(caller);
    }
    String email = Emails.normalize(rawEmail);
    if (email == null) {
      throw new ValidationException(List.of(new FieldError("email", "INVALID", "A valid email address is required")));
    }
    Instant now = clock.instant();
    return adminTx.write(caller.tenantAs(role), () -> {
      Optional<AccountSummary> found = directory.findAccount(email);
      UUID target = found.map(AccountSummary::accountId).orElse(null);
      directory.audit(caller.accountId(), "ACCOUNT_LOOKUP", "account", target,
          json("emailMasked", Emails.mask(email), "found", String.valueOf(found.isPresent())));
      event(caller.accountId(), "ACCOUNT_LOOKUP", "account", target, "", target, now);
      return found;
    });
  }

  private Tenant superAdmin(AuthContext caller) {
    AdminGuard.require(caller);
    elevations.requireActive(caller);
    return caller.tenantAs(Tenant.SUPER_ADMIN);
  }

  private void event(UUID adminId, String action, String targetType, UUID targetId, String reason, UUID accountId,
      Instant now) {
    adminOutbox.append(IdentityEvents.ADMIN_ACTION, accountId, adminId, 1,
        new AdminActionV1(action, adminId, targetType, targetId, reason, now.toString()));
  }

  private static String displayName(String raw, List<FieldError> errors) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    return displayNameOrEmpty(raw, errors);
  }

  private static String displayNameOrEmpty(String raw, List<FieldError> errors) {
    String s = raw.strip();
    if (s.length() > 80) {
      errors.add(new FieldError("displayName", "LENGTH", "At most 80 characters"));
    }
    return s;
  }

  private static List<String> roles(List<String> raw, List<FieldError> errors) {
    List<String> out = raw == null ? List.of() : raw.stream().distinct().toList();
    for (String r : out) {
      if (!STANDING_ROLES.contains(r)) {
        errors.add(new FieldError("roles", "INVALID",
            "Unknown role " + r + " (standing roles: SUPPORT, CATALOG_EDITOR, FINANCE; SUPER_ADMIN is break-glass only)"));
      }
    }
    return out;
  }

  /** Small JSON object of string values (audit details). */
  static String json(String... kv) {
    StringBuilder b = new StringBuilder("{");
    for (int i = 0; i + 1 < kv.length; i += 2) {
      if (i > 0) {
        b.append(',');
      }
      b.append(quote(kv[i])).append(':').append(quote(kv[i + 1]));
    }
    return b.append('}').toString();
  }

  private static String quote(String s) {
    StringBuilder b = new StringBuilder("\"");
    for (char c : s.toCharArray()) {
      switch (c) {
        case '"' -> b.append("\\\"");
        case '\\' -> b.append("\\\\");
        default -> {
          if (c < 0x20) {
            b.append(String.format("\\u%04x", (int) c));
          } else {
            b.append(c);
          }
        }
      }
    }
    return b.append('"').toString();
  }
}
