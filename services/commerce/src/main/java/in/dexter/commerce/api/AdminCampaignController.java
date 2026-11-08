package in.dexter.commerce.api;

import in.dexter.commerce.app.AdAdminService;
import in.dexter.commerce.app.CommerceViews.CampaignView;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** /v1/admin/commerce/campaigns (operators): campaign CRUD and creatives. */
@RestController
@RequestMapping(AdAdminService.PATH)
public class AdminCampaignController {
  private final AdAdminService ads;

  public AdminCampaignController(AdAdminService ads) {
    this.ads = ads;
  }

  @PostMapping
  @RequiresPermission("commerce:write")
  public ResponseEntity<CampaignView> create(AuthContext caller, @RequestBody AdAdminService.CampaignInput body) {
    CampaignView v = ads.create(Callers.operator(caller, Tenant.CATALOG_EDITOR), body);
    return ResponseEntity.created(URI.create(AdAdminService.PATH + "/" + v.id())).eTag(ETags.of(v.version()))
        .body(v);
  }

  @GetMapping
  @RequiresPermission("commerce:read")
  public PageResponse<CampaignView> list(AuthContext caller, PageRequest page) {
    return PageResponse.from(ads.list(Callers.operator(caller, Tenant.CATALOG_EDITOR), page));
  }

  @GetMapping("/{id}")
  @RequiresPermission("commerce:read")
  public ResponseEntity<CampaignView> get(AuthContext caller, @PathVariable("id") UUID id) {
    CampaignView v = ads.get(Callers.operator(caller, Tenant.CATALOG_EDITOR), id);
    return ResponseEntity.ok().eTag(ETags.of(v.version())).body(v);
  }

  @PutMapping("/{id}")
  @RequiresPermission("commerce:write")
  public ResponseEntity<CampaignView> update(AuthContext caller, @PathVariable("id") UUID id,
      @IfMatchVersion long version, @RequestBody AdAdminService.CampaignInput body) {
    CampaignView v = ads.update(Callers.operator(caller, Tenant.CATALOG_EDITOR), id, version, body);
    return ResponseEntity.ok().eTag(ETags.of(v.version())).body(v);
  }

  @DeleteMapping("/{id}")
  @RequiresPermission("commerce:write")
  public ResponseEntity<Void> end(AuthContext caller, @PathVariable("id") UUID id) {
    ads.end(Callers.operator(caller, Tenant.CATALOG_EDITOR), id);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/{id}/creatives")
  @RequiresPermission("commerce:write")
  public ResponseEntity<CampaignView> addCreative(AuthContext caller, @PathVariable("id") UUID id,
      @RequestBody AdAdminService.NewCreative body) {
    CampaignView v = ads.addCreative(Callers.operator(caller, Tenant.CATALOG_EDITOR), id, body);
    return ResponseEntity.status(201).eTag(ETags.of(v.version())).body(v);
  }

  @PutMapping("/{id}/creatives/{creativeId}")
  @RequiresPermission("commerce:write")
  public ResponseEntity<CampaignView> updateCreative(AuthContext caller, @PathVariable("id") UUID id,
      @PathVariable("creativeId") UUID creativeId, @IfMatchVersion long version,
      @RequestBody AdAdminService.CreativeChange body) {
    CampaignView v = ads.updateCreative(Callers.operator(caller, Tenant.CATALOG_EDITOR), id, creativeId, version,
        body);
    return ResponseEntity.ok().body(v);
  }
}
