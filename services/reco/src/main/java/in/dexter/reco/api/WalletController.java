package in.dexter.reco.api;

import in.dexter.core.error.CoreErrors;
import in.dexter.core.error.DexterException;
import in.dexter.reco.app.BestCardService;
import in.dexter.reco.app.RecommendationService;
import in.dexter.reco.app.WalletService;
import in.dexter.security.AuthContext;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * P15's two read endpoints, on the same {@code /v1/recommendations} path as P14's (MASTER_PLAN P15):
 *
 * <ul>
 *   <li>{@code GET /v1/recommendations/wallet} — the best one-, two- and three-card wallet, with the marginal
 *       value of each further card;</li>
 *   <li>{@code GET /v1/recommendations/best-card?merchantId=|category=&amountPaise=} — which held card to put
 *       this purchase on, given the cap left this month, the active offers and any milestone it would reach.</li>
 * </ul>
 *
 * <p>Both are reads and neither is cached: the wallet because it is cheap and deliberate, best-card-now because
 * the answer depends on the amount (ADR-0021 §5).
 */
@RestController
@RequestMapping(RecommendationService.PATH)
public class WalletController {
  private final WalletService wallets;
  private final BestCardService bestCards;

  public WalletController(WalletService wallets, BestCardService bestCards) {
    this.wallets = wallets;
    this.bestCards = bestCards;
  }

  @GetMapping("/wallet")
  public WalletService.View wallet(AuthContext caller,
      @RequestParam(name = "size", required = false) Integer size) {
    int maxSize = size == null ? WalletService.DEFAULT_MAX_SIZE : size;
    if (maxSize < 1 || maxSize > WalletService.DEFAULT_MAX_SIZE) {
      throw new DexterException(CoreErrors.VALIDATION_FAILED,
          "size is 1 to " + WalletService.DEFAULT_MAX_SIZE);
    }
    return wallets.wallet(Callers.user(caller), maxSize);
  }

  @GetMapping("/best-card")
  public BestCardService.View bestCard(AuthContext caller,
      @RequestParam(name = "merchantId", required = false) String merchantId,
      @RequestParam(name = "category", required = false) String category,
      @RequestParam(name = "amountPaise") long amountPaise) {
    UUID merchant = merchantId == null || merchantId.isBlank() ? null : Callers.id(merchantId);
    return bestCards.bestCard(Callers.user(caller), merchant, category, amountPaise);
  }
}
