package in.dexter.commerce.api;

import in.dexter.commerce.app.CommerceViews.StatsView;
import in.dexter.commerce.app.StatsService;
import in.dexter.core.tenant.Tenant;
import in.dexter.security.AuthContext;
import in.dexter.security.web.RequiresPermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /v1/admin/commerce/stats} (operators): clicks, uniques, conversions, commission, impressions. */
@RestController
@RequestMapping(StatsService.PATH)
public class AdminStatsController {
  private final StatsService stats;

  public AdminStatsController(StatsService stats) {
    this.stats = stats;
  }

  @GetMapping
  @RequiresPermission("commerce:read")
  public StatsView stats(AuthContext caller, @RequestParam(value = "from", required = false) String from,
      @RequestParam(value = "to", required = false) String to) {
    return stats.stats(Callers.operator(caller, Tenant.CATALOG_EDITOR), from, to);
  }
}
