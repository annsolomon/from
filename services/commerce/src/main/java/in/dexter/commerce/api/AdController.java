package in.dexter.commerce.api;

import in.dexter.commerce.app.AdService;
import in.dexter.commerce.app.CommerceViews.AdServeView;
import in.dexter.commerce.app.Viewer;
import in.dexter.security.web.PublicEndpoint;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /v1/ads/serve?placement=} (MASTER_PLAN P17): one ad for a slot, or none and why. Public. */
@RestController
@RequestMapping(AdService.PATH)
public class AdController {
  private final AdService ads;

  public AdController(AdService ads) {
    this.ads = ads;
  }

  @GetMapping("/serve")
  @PublicEndpoint
  public ResponseEntity<AdServeView> serve(Viewer viewer, @RequestParam("placement") String placement) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ads.serve(viewer, placement));
  }
}
