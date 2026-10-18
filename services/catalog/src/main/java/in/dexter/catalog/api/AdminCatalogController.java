package in.dexter.catalog.api;

import in.dexter.catalog.app.AdminCatalogStore;
import in.dexter.catalog.app.CatalogAdminService;
import in.dexter.catalog.domain.CardDocument;
import in.dexter.contracts.events.catalog.OfferChangedV1;
import in.dexter.core.error.CoreErrors;
import in.dexter.core.error.DexterException;
import in.dexter.db.page.PageRequest;
import in.dexter.security.AuthContext;
import in.dexter.security.web.RequiresPermission;
import in.dexter.web.etag.ETags;
import in.dexter.web.etag.IfMatchVersion;
import in.dexter.web.page.PageResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * /v1/admin/catalog (MASTER_PLAN P09, admin token with catalog permissions): issuers, card drafts (If-Match on the
 * draft version), {@code :publish} / {@code :retire}, versions, offers.
 */
@RestController
@RequestMapping("/v1/admin/catalog")
public class AdminCatalogController {
  private final CatalogAdminService admin;

  public AdminCatalogController(CatalogAdminService admin) {
    this.admin = admin;
  }

  @PostMapping("/issuers")
  @RequiresPermission("catalog:write")
  public ResponseEntity<AdminCatalogStore.Issuer> createIssuer(AuthContext caller,
      @RequestBody CatalogAdminService.NewIssuer body) {
    return ResponseEntity.status(HttpStatus.CREATED).body(admin.createIssuer(Callers.editor(caller), body));
  }

  @GetMapping("/cards")
  @RequiresPermission("catalog:read")
  public PageResponse<CatalogAdminService.CardSummary> cards(AuthContext caller, PageRequest page,
      @RequestParam(value = "status", required = false) String status) {
    return PageResponse.from(admin.list(Callers.editor(caller), page, status));
  }

  /** A new card: a draft only, invisible to the public until published. */
  @PostMapping("/cards")
  @RequiresPermission("catalog:write")
  public ResponseEntity<CatalogAdminService.DraftView> create(AuthContext caller, @RequestBody CardDocument body) {
    CatalogAdminService.DraftView v = admin.create(Callers.editor(caller), body);
    return ResponseEntity.status(HttpStatus.CREATED).eTag(ETags.of(v.draftVersion()))
        .header("Location", "/v1/admin/catalog/cards/" + v.cardId() + "/draft").body(v);
  }

  @GetMapping("/cards/{id}/draft")
  @RequiresPermission("catalog:read")
  public ResponseEntity<CatalogAdminService.DraftView> draft(AuthContext caller, @PathVariable("id") UUID id) {
    CatalogAdminService.DraftView v = admin.draft(Callers.editor(caller), id);
    return ResponseEntity.ok().eTag(ETags.of(v.draftVersion())).body(v);
  }

  @PutMapping("/cards/{id}/draft")
  @RequiresPermission("catalog:write")
  public ResponseEntity<CatalogAdminService.DraftView> saveDraft(AuthContext caller, @PathVariable("id") UUID id,
      @IfMatchVersion long version, @RequestBody CardDocument body) {
    CatalogAdminService.DraftView v = admin.saveDraft(Callers.editor(caller), id, version, body);
    return ResponseEntity.ok().eTag(ETags.of(v.draftVersion())).body(v);
  }

  /** Discards a published card's unpublished edits (P10: the review approve needs a clean draft). */
  @DeleteMapping("/cards/{id}/draft")
  @RequiresPermission("catalog:write")
  public ResponseEntity<Void> discardDraft(AuthContext caller, @PathVariable("id") UUID id) {
    admin.discardDraft(Callers.editor(caller), id);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/cards/{id}/versions")
  @RequiresPermission("catalog:read")
  public Map<String, List<CatalogAdminService.VersionView>> versions(AuthContext caller,
      @PathVariable("id") UUID id) {
    return Map.of("data", admin.versions(Callers.editor(caller), id));
  }

  /** {@code POST /v1/admin/catalog/cards/{id}:publish} and {@code :retire} (AIP-136 custom methods). */
  @PostMapping("/cards/{idAction}")
  @RequiresPermission("catalog:publish")
  public CatalogAdminService.PublishResult action(AuthContext caller, @PathVariable("idAction") String idAction) {
    int colon = idAction.indexOf(':');
    String action = colon < 0 ? "" : idAction.substring(colon + 1);
    UUID id = id(colon < 0 ? idAction : idAction.substring(0, colon));
    return switch (action) {
      case "publish" -> admin.publish(Callers.editor(caller), id);
      case "retire" -> admin.retire(Callers.editor(caller), id);
      default -> throw new DexterException(CoreErrors.NOT_FOUND, "Unknown card action");
    };
  }

  @PostMapping("/offers")
  @RequiresPermission("catalog:write")
  public ResponseEntity<OfferChangedV1> createOffer(AuthContext caller,
      @RequestBody CatalogAdminService.NewOffer body) {
    return ResponseEntity.status(HttpStatus.CREATED).body(admin.createOffer(Callers.editor(caller), body));
  }

  /** {@code POST /v1/admin/catalog/offers/{id}:withdraw}. */
  @PostMapping("/offers/{idAction}")
  @RequiresPermission("catalog:write")
  public OfferChangedV1 offerAction(AuthContext caller, @PathVariable("idAction") String idAction) {
    if (!idAction.endsWith(":withdraw")) {
      throw new DexterException(CoreErrors.NOT_FOUND, "Unknown offer action");
    }
    return admin.withdrawOffer(Callers.editor(caller), id(idAction.substring(0, idAction.indexOf(':'))));
  }

  private static UUID id(String s) {
    try {
      return UUID.fromString(s);
    } catch (IllegalArgumentException e) {
      throw new DexterException(CoreErrors.INVALID_ID, "Not an id");
    }
  }
}
