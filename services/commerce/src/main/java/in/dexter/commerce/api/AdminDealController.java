package in.dexter.commerce.api;

import in.dexter.commerce.app.CommerceViews.AdminDealView;
import in.dexter.commerce.app.DealAdminService;
import in.dexter.core.tenant.Tenant;
import in.dexter.db.page.PageRequest;
import in.dexter.security.AuthContext;
import in.dexter.security.web.RequiresPermission;
import in.dexter.web.etag.ETags;
import in.dexter.web.etag.IfMatchVersion;
import in.dexter.web.page.PageResponse;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** /v1/admin/commerce/deals (operators): create, list, read, move status (If-Match). */
@RestController
@RequestMapping(DealAdminService.PATH)
public class AdminDealController {
  private final DealAdminService deals;

  public AdminDealController(DealAdminService deals) {
    this.deals = deals;
  }

  @PostMapping
  @RequiresPermission("commerce:write")
  public ResponseEntity<AdminDealView> create(AuthContext caller, @RequestBody DealAdminService.NewDeal body) {
    AdminDealView v = deals.create(Callers.operator(caller, Tenant.CATALOG_EDITOR), body);
    return ResponseEntity.created(URI.create(DealAdminService.PATH + "/" + v.id())).eTag(ETags.of(v.version()))
        .body(v);
  }

  @GetMapping
  @RequiresPermission("commerce:read")
  public PageResponse<AdminDealView> list(AuthContext caller, PageRequest page) {
    return PageResponse.from(deals.list(Callers.operator(caller, Tenant.CATALOG_EDITOR), page));
  }

  @GetMapping("/{id}")
  @RequiresPermission("commerce:read")
  public ResponseEntity<AdminDealView> get(AuthContext caller, @PathVariable("id") UUID id) {
    AdminDealView v = deals.get(Callers.operator(caller, Tenant.CATALOG_EDITOR), id);
    return ResponseEntity.ok().eTag(ETags.of(v.version())).body(v);
  }

  @PostMapping("/{id}:status")
  @RequiresPermission("commerce:write")
  public ResponseEntity<AdminDealView> status(AuthContext caller, @PathVariable("id") UUID id,
      @IfMatchVersion long version, @RequestBody DealAdminService.StatusChange body) {
    AdminDealView v = deals.setStatus(Callers.operator(caller, Tenant.CATALOG_EDITOR), id, version, body);
    return ResponseEntity.ok().eTag(ETags.of(v.version())).body(v);
  }
}
