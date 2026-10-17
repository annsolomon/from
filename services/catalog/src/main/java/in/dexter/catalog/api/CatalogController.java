package in.dexter.catalog.api;

import in.dexter.catalog.app.CardFilter;
import in.dexter.catalog.app.CatalogQueryService;
import in.dexter.catalog.app.CatalogStore;
import in.dexter.db.page.PageRequest;
import in.dexter.security.web.PublicEndpoint;
import in.dexter.web.etag.ETags;
import in.dexter.web.page.PageResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.node.ObjectNode;

/**
 * The public catalog (MASTER_PLAN P09): no token (the gateway route is public too). Published data only (RLS on the
 * app pool). Cards are their current published snapshot plus {@code version}; the detail carries it as the ETag.
 */
@RestController
@RequestMapping("/v1/catalog")
@PublicEndpoint
public class CatalogController {
  private final CatalogQueryService catalog;

  public CatalogController(CatalogQueryService catalog) {
    this.catalog = catalog;
  }

  @GetMapping("/cards")
  public PageResponse<ObjectNode> cards(PageRequest page,
      @RequestParam(value = "network", required = false) String network,
      @RequestParam(value = "issuer", required = false) String issuer,
      @RequestParam(value = "maxAnnualFee", required = false) Long maxAnnualFee,
      @RequestParam(value = "category", required = false) String category,
      @RequestParam(value = "minRateBps", required = false) Integer minRateBps) {
    return PageResponse.from(catalog.list(page, new CardFilter(network, issuer, maxAnnualFee, category, minRateBps)));
  }

  @GetMapping("/cards/search")
  public Map<String, List<CatalogStore.SearchHit>> search(@RequestParam("q") String q) {
    return Map.of("data", catalog.search(q));
  }

  @GetMapping("/cards/{slug}")
  public ResponseEntity<ObjectNode> card(@PathVariable("slug") String slug) {
    CatalogQueryService.CardView v = catalog.bySlug(slug);
    return ResponseEntity.ok().eTag(ETags.of(v.version())).cacheControl(CacheControl.noCache()).body(v.card());
  }

  @GetMapping("/offers")
  public Map<String, List<CatalogStore.Offer>> offers(
      @RequestParam(value = "merchantId", required = false) UUID merchantId,
      @RequestParam(value = "category", required = false) String category) {
    return Map.of("data", catalog.offers(merchantId, category));
  }

  /** The best published rate per card for a category (MASTER_PLAN P09: replaces the old reco endpoint). */
  @GetMapping("/categories/potential")
  public CatalogQueryService.PotentialView potential(@RequestParam("category") String category) {
    return catalog.potential(category);
  }
}
