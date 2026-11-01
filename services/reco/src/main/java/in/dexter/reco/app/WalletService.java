package in.dexter.reco.app;

import in.dexter.core.tenant.Tenant;
import in.dexter.db.tenant.TenantTx;
import in.dexter.reco.domain.Optimizer;
import in.dexter.reco.domain.Savings;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code GET /v1/recommendations/wallet} (MASTER_PLAN P15): the best one-, two- and three-card wallet for this
 * account, and what each further card actually adds.
 *
 * <p>Not cached. The P14 ranking is cached because it is the same question every time and the answer is wanted on
 * every screen; a wallet is asked for deliberately and takes milliseconds over a beam of fifteen cards, so a
 * second cache keyed by a second hash would be two things to keep coherent for no gain (ADR-0021 §5). The read is
 * a read transaction — it writes nothing and marks nobody dirty.
 */
public final class WalletService {
  /** The plan's sizes; a caller may ask for fewer. */
  public static final int DEFAULT_MAX_SIZE = Optimizer.MAX_WALLET;

  private final TenantTx tx;
  private final RecommendationService recommendations;

  public WalletService(TenantTx tx, RecommendationService recommendations) {
    this.tx = tx;
    this.recommendations = recommendations;
  }

  /** One card's place in a wallet. */
  public record Seat(String cardProductId, String slug, String name, boolean held, List<String> categoryRoots,
      long annualValuePaise, long annualRewardPaise, long expectedFeePaise, List<String> reasons) {}

  /**
   * One wallet size.
   *
   * @param marginalValuePaise what this size adds over the best wallet one card smaller; negative when a further
   *     card costs more in fee than it earns, which is an answer worth giving
   * @param exact false when too many categories were contested to route them exhaustively
   */
  public record WalletView(int size, List<Seat> cards, long annualValuePaise, long annualRewardPaise,
      long annualFeePaise, long marginalValuePaise, boolean exact, Map<String, String> routing) {}

  /** The whole answer. */
  public record View(String window, long monthlySpendPaise, int bestSize, List<WalletView> bySize,
      int candidatesConsidered, int inBeam, List<String> redundant) {}

  public View wallet(Tenant tenant, int maxSize) {
    if (maxSize < 1 || maxSize > Optimizer.MAX_WALLET) {
      throw new IllegalArgumentException("a wallet is 1 to " + Optimizer.MAX_WALLET + " cards");
    }
    return tx.read(tenant, () -> {
      RecommendationService.Inputs in = recommendations.inputs(tenant.accountId());
      Optimizer.Result r = Optimizer.optimize(in.candidates(), in.spend(), in.heldProductIds(), maxSize);
      List<WalletView> bySize = new ArrayList<>(r.bySize().size());
      for (Optimizer.Wallet w : r.bySize()) {
        List<Seat> seats = new ArrayList<>(w.seats().size());
        for (Optimizer.Seat s : w.seats()) {
          Savings.Result sv = s.savings();
          seats.add(new Seat(s.cardProductId(), s.slug(), s.name(), s.held(), s.categoryRoots(),
              s.annualValuePaise(), sv.annualRewardPaise(), sv.fee().expectedFeePaise(), seatReasons(s, sv)));
        }
        bySize.add(new WalletView(w.size(), seats, w.annualValuePaise(), w.annualRewardPaise(),
            w.annualFeePaise(), w.marginalValuePaise(), w.exact(), w.routing()));
      }
      Optimizer.Wallet best = r.best();
      return new View(RecommendationService.SCORING_WINDOW, in.spend().monthlyTotal(),
          best == null ? 0 : best.size(), bySize, r.considered(), r.inBeam(), r.dropped());
    });
  }

  /** Why this card is in the wallet: what it was given, and what the fee and milestone did to its value. */
  private static List<String> seatReasons(Optimizer.Seat s, Savings.Result sv) {
    List<String> out = new ArrayList<>(3);
    if (s.categoryRoots().isEmpty()) {
      out.add("nothing is routed to this card, so it only costs its fee");
    } else {
      out.add("carries " + String.join(", ", s.categoryRoots()));
    }
    if (sv.fee().annualFeePaise() > 0) {
      out.add(sv.fee().expectedFeePaise() == 0 ? "its fee is waived by the spend routed here"
          : "fee after the waiver probability: " + sv.fee().expectedFeePaise() + " paise");
    }
    if (sv.milestone().annualValuePaise() > 0) {
      out.add("reaches its spend milestone");
    } else if (sv.milestone().monthlyShortfallPaise() > 0) {
      out.add("short of its milestone by " + sv.milestone().monthlyShortfallPaise() + " paise a month");
    }
    return out;
  }
}
