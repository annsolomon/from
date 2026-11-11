package in.dexter.audit.api;

import in.dexter.audit.app.AuditStore.Filter;
import in.dexter.audit.app.AuditViews.AdminPrivacyView;
import in.dexter.audit.app.AuditViews.EntryView;
import in.dexter.audit.app.AuditViews.VerifyView;
import in.dexter.audit.app.AuditLogService;
import in.dexter.audit.app.PrivacyService;
import in.dexter.audit.domain.AuditErrors;
import in.dexter.core.error.DexterException;
import in.dexter.db.page.PageRequest;
import in.dexter.security.AuthContext;
import in.dexter.security.web.RequiresPermission;
import in.dexter.web.page.PageResponse;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The audit log for operators (MASTER_PLAN P18): {@code GET /v1/admin/audit} (keyset, newest first; filters
 * actorId, action, targetType + targetId, from / to), {@code POST /v1/admin/audit:verify-chain} and a privacy
 * request's state and {@code :resume}.
 */
@RestController
public class AdminAuditController {
  private final AuditLogService audit;
  private final PrivacyService privacy;

  public AdminAuditController(AuditLogService audit, PrivacyService privacy) {
    this.audit = audit;
    this.privacy = privacy;
  }

  @GetMapping(AuditLogService.PATH)
  @RequiresPermission("audit:read")
  public PageResponse<EntryView> list(AuthContext caller, PageRequest page,
      @RequestParam(name = "actorId", required = false) @Nullable String actorId,
      @RequestParam(name = "action", required = false) @Nullable String action,
      @RequestParam(name = "targetType", required = false) @Nullable String targetType,
      @RequestParam(name = "targetId", required = false) @Nullable String targetId,
      @RequestParam(name = "from", required = false) @Nullable String from,
      @RequestParam(name = "to", required = false) @Nullable String to) {
    Filter f = new Filter(blank(actorId), blank(action), blank(targetType), blank(targetId), instant("from", from),
        instant("to", to));
    return PageResponse.from(audit.list(Callers.operator(caller), f, page));
  }

  @PostMapping(AuditLogService.PATH + ":verify-chain")
  @RequiresPermission("audit:read")
  public VerifyView verify(AuthContext caller) {
    return audit.verify(Callers.operator(caller), Callers.operatorId(caller));
  }

  @GetMapping(PrivacyService.ADMIN_PATH + "/{id}")
  @RequiresPermission("audit:read")
  public AdminPrivacyView privacyRequest(AuthContext caller, @PathVariable("id") UUID id) {
    return privacy.adminGet(Callers.operator(caller), id);
  }

  @PostMapping(PrivacyService.ADMIN_PATH + "/{id}:resume")
  @RequiresPermission("audit:verify")
  public AdminPrivacyView resume(AuthContext caller, @PathVariable("id") UUID id) {
    return privacy.resume(Callers.operator(caller), Callers.operatorId(caller), id);
  }

  static @Nullable String blank(@Nullable String s) {
    return s == null || s.isBlank() ? null : s.strip();
  }

  static @Nullable Instant instant(String name, @Nullable String s) {
    if (s == null || s.isBlank()) {
      return null;
    }
    try {
      return Instant.parse(s.strip());
    } catch (DateTimeParseException e) {
      throw new DexterException(AuditErrors.AUDIT_FILTER_INVALID, name + " must be an ISO-8601 instant");
    }
  }
}
