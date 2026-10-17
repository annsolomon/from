package in.dexter.identity.api;

import in.dexter.db.page.PageRequest;
import in.dexter.identity.app.AdminDirectory.AccountSummary;
import in.dexter.identity.app.AdminDirectory.AdminView;
import in.dexter.identity.app.AdminDirectoryService;
import in.dexter.identity.domain.AdminPermissions;
import in.dexter.security.AuthContext;
import in.dexter.security.web.RequiresPermission;
import in.dexter.web.etag.ETags;
import in.dexter.web.etag.IfMatchVersion;
import in.dexter.web.page.PageResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin endpoints acting on others (MASTER_PLAN P05). Each needs an admin-console token with MFA, the listed
 * permission, and — for admin management — an active break-glass elevation; the identity_admin database policies
 * check the actor role again.
 */
@RestController
@RequestMapping("/v1/admin")
public class AdminController {
  private final AdminDirectoryService admins;

  public AdminController(AdminDirectoryService admins) {
    this.admins = admins;
  }

  @RequiresPermission(AdminPermissions.ADMINS_READ)
  @GetMapping("/admins")
  public PageResponse<AdminView> list(AuthContext caller, PageRequest page) {
    return PageResponse.from(admins.list(caller, page));
  }

  @RequiresPermission(AdminPermissions.ADMINS_READ)
  @GetMapping("/admins/{id}")
  public ResponseEntity<AdminView> get(AuthContext caller, @PathVariable("id") UUID id) {
    AdminView v = admins.get(caller, id);
    return ResponseEntity.ok().eTag(ETags.of(v.version())).body(v);
  }

  /** Invites an admin (email with a single-use link). Roles: SUPPORT, CATALOG_EDITOR, FINANCE. */
  @RequiresPermission(AdminPermissions.ADMINS_WRITE)
  @PostMapping("/admins")
  public ResponseEntity<AdminView> create(AuthContext caller, @RequestBody AuthRequests.NewAdmin body) {
    AdminView v = admins.create(caller, new AdminDirectoryService.NewAdmin(body.email(), body.displayName(),
        body.roles() == null ? List.of() : body.roles(), Boolean.TRUE.equals(body.breakGlassEligible())));
    return ResponseEntity.status(HttpStatus.CREATED).eTag(ETags.of(v.version())).body(v);
  }

  /** Versioned (If-Match) change: displayName, status (active/disabled), roles, breakGlassEligible, resetMfa. */
  @RequiresPermission(AdminPermissions.ADMINS_WRITE)
  @PatchMapping(value = "/admins/{id}", consumes = {MeController.MERGE_PATCH, "application/json"})
  public ResponseEntity<AdminView> update(AuthContext caller, @PathVariable("id") UUID id,
      @IfMatchVersion long version, @RequestBody AdminChangeBody body) {
    AdminView v = admins.update(caller, id, version, new AdminDirectoryService.AdminChange(body.displayName(),
        body.status(), body.roles(), body.breakGlassEligible(), body.resetMfa()));
    return ResponseEntity.ok().eTag(ETags.of(v.version())).body(v);
  }

  /** Support lookup by exact email (audited, also when nothing is found). */
  @RequiresPermission(AdminPermissions.ACCOUNTS_READ)
  @GetMapping("/accounts")
  public AccountSummary account(AuthContext caller, @RequestParam("email") String email) {
    return admins.lookupAccount(caller, email)
        .orElseThrow(() -> new in.dexter.core.error.NotFoundException("account"));
  }

  public record AdminChangeBody(String displayName, String status, List<String> roles, Boolean breakGlassEligible,
      Boolean resetMfa) {}
}
