package in.dexter.ledger.api;

import in.dexter.ledger.app.MerchantService;
import in.dexter.security.AuthContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** /v1/merchants: autocomplete (top 8, Redis-cached) and the resolver (P08). */
@RestController
@RequestMapping("/v1/merchants")
public class MerchantController {
  private final MerchantService merchants;

  public MerchantController(MerchantService merchants) {
    this.merchants = merchants;
  }

  @GetMapping("/autocomplete")
  public MerchantService.Suggestions autocomplete(AuthContext caller, @RequestParam("q") String q) {
    return merchants.autocomplete(Callers.user(caller), q);
  }

  /** What the resolver makes of a raw merchant string (no write; the user's rules apply). */
  @PostMapping("/resolve")
  public MerchantService.ResolveResponse resolve(AuthContext caller,
      @RequestBody MerchantService.ResolveRequest body) {
    return merchants.resolve(Callers.user(caller), body);
  }
}
