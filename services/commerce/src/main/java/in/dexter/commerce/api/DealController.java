package in.dexter.commerce.api;

import in.dexter.commerce.app.CommerceViews.DealDetailView;
import in.dexter.commerce.app.CommerceViews.DealView;
import in.dexter.commerce.app.DealService;
import in.dexter.commerce.app.Viewer;
import in.dexter.db.page.PageRequest;
import in.dexter.security.web.PublicEndpoint;
import in.dexter.web.page.PageResponse;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * /v1/deals (MASTER_PLAN P17): the ranked marketplace feed and one deal with its savings at a spend. Public; a user
 * token, when sent, personalises card-match ({@link Viewer}).
 */
@RestController
@RequestMapping(DealService.PATH)
public class DealController {
  private final DealService deals;

  public DealController(DealService deals) {
    this.deals = deals;
  }

  @GetMapping
  @PublicEndpoint
  public PageResponse<DealView> feed(Viewer viewer, PageRequest page,
      @RequestParam(value = "category", required = false) String category) {
    return PageResponse.from(deals.feed(viewer, page, category));
  }

  @GetMapping("/{id}")
  @PublicEndpoint
  public DealDetailView get(Viewer viewer, @PathVariable("id") UUID id,
      @RequestParam(value = "spendPaise", required = false) Long spendPaise) {
    return deals.get(viewer, id, spendPaise);
  }
}
