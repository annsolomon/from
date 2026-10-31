package in.dexter.reco.api;

import in.dexter.reco.app.RecommendationService;
import in.dexter.security.AuthContext;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * /v1/recommendations (MASTER_PLAN P14). Reads only: the recompute is event-driven (the sweeper) or happens inside
 * the read when the stored result was built from different inputs, so there is nothing here to POST.
 *
 * <ul>
 *   <li>{@code GET /v1/recommendations} — the ranking and the not-recommended bucket;</li>
 *   <li>{@code GET /v1/recommendations?cardProductIds=a,b} — score just those, for ranking a search result;</li>
 *   <li>{@code GET /v1/recommendations/explain/{cardProductId}} — the per-category breakdown, the caps that bound
 *       it, the fee and the reasons.</li>
 * </ul>
 */
@RestController
@RequestMapping(RecommendationService.PATH)
public class RecommendationController {
  private final RecommendationService recommendations;

  public RecommendationController(RecommendationService recommendations) {
    this.recommendations = recommendations;
  }

  @GetMapping
  public RecommendationService.View get(AuthContext caller,
      @RequestParam(name = "cardProductIds", required = false) String cardProductIds) {
    return recommendations.get(Callers.user(caller), Callers.ids(cardProductIds));
  }

  @GetMapping("/explain/{cardProductId}")
  public RecommendationService.Explanation explain(AuthContext caller,
      @PathVariable("cardProductId") UUID cardProductId) {
    return recommendations.explain(Callers.user(caller), cardProductId);
  }
}
