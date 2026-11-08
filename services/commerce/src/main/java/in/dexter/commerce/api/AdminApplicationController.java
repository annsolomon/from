package in.dexter.commerce.api;

import in.dexter.commerce.app.ApplicationAdminService;
import in.dexter.commerce.app.CommerceViews.AdminApplicationView;
import in.dexter.core.tenant.Tenant;
import in.dexter.db.page.PageRequest;
import in.dexter.security.AuthContext;
import in.dexter.security.web.RequiresPermission;
import in.dexter.web.etag.ETags;
import in.dexter.web.etag.IfMatchVersion;
import in.dexter.web.page.PageResponse;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** /v1/admin/commerce/card-applications (operators): the open queue, one application, the decision (If-Match). */
@RestController
@RequestMapping(ApplicationAdminService.PATH)
public class AdminApplicationController {
  private final ApplicationAdminService apps;

  public AdminApplicationController(ApplicationAdminService apps) {
    this.apps = apps;
  }

  @GetMapping
  @RequiresPermission("commerce:read")
  public PageResponse<AdminApplicationView> queue(AuthContext caller, PageRequest page) {
    return PageResponse.from(apps.queue(Callers.operator(caller, Tenant.SUPPORT), page));
  }

  @GetMapping("/{id}")
  @RequiresPermission("commerce:read")
  public ResponseEntity<AdminApplicationView> get(AuthContext caller, @PathVariable("id") UUID id) {
    AdminApplicationView v = apps.get(Callers.operator(caller, Tenant.SUPPORT), id);
    return ResponseEntity.ok().eTag(ETags.of(v.version())).body(v);
  }

  @PostMapping("/{id}:decide")
  @RequiresPermission("commerce:decide")
  public ResponseEntity<AdminApplicationView> decide(AuthContext caller, @PathVariable("id") UUID id,
      @IfMatchVersion long version, @RequestBody ApplicationAdminService.Decision body) {
    AdminApplicationView v = apps.decide(Callers.operator(caller, Tenant.SUPPORT), id, version, body);
    return ResponseEntity.ok().eTag(ETags.of(v.version())).body(v);
  }
}
