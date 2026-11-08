package in.dexter.commerce.app;

import in.dexter.commerce.app.CommerceViews.Component;
import in.dexter.commerce.app.CommerceViews.DealDetailView;
import in.dexter.commerce.app.CommerceViews.DealView;
import in.dexter.commerce.app.CommerceViews.EligibleCard;
import in.dexter.commerce.app.CommerceViews.SavingLine;
import in.dexter.commerce.app.DealStore.Deal;
import in.dexter.commerce.domain.CommerceErrors;
import in.dexter.commerce.domain.DealRanking;
import in.dexter.commerce.domain.Savings;
import in.dexter.core.error.CoreErrors;
import in.dexter.core.error.DexterException;
import in.dexter.core.error.ValidationException;
import in.dexter.db.page.CursorPosition;
import in.dexter.db.page.KeysetCursorCodec;
import in.dexter.db.page.Page;
import in.dexter.db.page.PageRequest;
import in.dexter.db.tenant.TenantTx;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * {@code GET /v1/deals} (the ranked marketplace feed) and {@code GET /v1/deals/{id}} (MASTER_PLAN P17). Both are
 * public; a user token personalises card-match and puts the viewer in the click links.
 *
 * <p>The feed ranks in memory over the bounded active set (at most {@code maxActive} deals, read on deals_active)
 * and pages it with a signed keyset cursor over (score, priority, id): the order is total, so a page never repeats
 * or skips a deal of an unchanged set. A deal added, ended or re-scored between pages lands where its key puts it.
 */
public final class DealService {
  public static final String PATH = "/v1/deals";
  public static final String SORT = "relevance";
  private static final Pattern CATEGORY = Pattern.compile("[a-z0-9_]{1,40}(\\.[a-z0-9_]{1,40}){0,3}");

  private final TenantTx tx;
  private final DealStore deals;
  private final ClickLinks links;
  private final KeysetCursorCodec codec;
  private final Clock clock;
  private final int maxActive;

  public DealService(TenantTx tx, DealStore deals, ClickLinks links, KeysetCursorCodec codec, Clock clock,
      int maxActive) {
    this.tx = tx;
    this.deals = deals;
    this.links = links;
    this.codec = codec;
    this.clock = clock;
    this.maxActive = maxActive;
  }

  public Page<DealView> feed(Viewer viewer, PageRequest page, @Nullable String category) {
    String cat = category(category);
    String filterHash = KeysetCursorCodec.filterHash(Map.of("category", cat == null ? "" : cat));
    DealRanking.Position after = page.isFirst() ? null : position(codec.decode(page.cursor(), SORT, filterHash));
    Instant now = clock.instant();
    return tx.read(viewer.tenant(), () -> {
      List<Deal> active = deals.active(cat, now, maxActive);
      Set<UUID> held = viewer.signedIn() ? deals.heldCardProducts() : Set.of();
      Map<UUID, Deal> byId = new HashMap<>();
      List<DealRanking.Candidate> candidates = new ArrayList<>(active.size());
      Set<UUID> cardIds = new LinkedHashSet<>();
      for (Deal d : active) {
        byId.put(d.id(), d);
        cardIds.addAll(d.eligibleCardProducts());
        candidates.add(new DealRanking.Candidate(d.id(), d.priority(),
            Savings.total(d.components(), d.referenceSpendPaise()), DealRanking.match(d.eligibleCardProducts(), held),
            d.sponsored(), d.sponsorWeight()));
      }
      Map<UUID, String> names = deals.publishedCardNames(cardIds);
      List<DealRanking.Ranked> ranked = DealRanking.rank(candidates);
      List<DealRanking.Ranked> slice = DealRanking.page(ranked, after, page.limit() + 1);
      boolean more = slice.size() > page.limit();
      List<DealRanking.Ranked> items = more ? slice.subList(0, page.limit()) : slice;
      List<DealView> views = items.stream().map(r -> view(byId.get(r.id()), r, names, viewer)).toList();
      String next = null;
      if (more) {
        DealRanking.Position p = DealRanking.positionOf(items.get(items.size() - 1));
        next = codec.encode(SORT, List.of(Integer.toString(p.scoreBps()), Integer.toString(p.priority()),
            p.id().toString()), filterHash);
      }
      return new Page<>(views, page.limit(), more, next);
    });
  }

  public DealDetailView get(Viewer viewer, UUID id, @Nullable Long spendPaise) {
    if (spendPaise != null && (spendPaise < 0 || spendPaise > 100_000_000_000L)) {
      throw ValidationException.of("spendPaise", "SPEND_INVALID", "0..100000000000");
    }
    Instant now = clock.instant();
    return tx.read(viewer.tenant(), () -> {
      Deal d = deals.findDeal(id).filter(x -> x.liveAt(now))
          .orElseThrow(() -> new DexterException(CommerceErrors.DEAL_NOT_FOUND, "No live deal with that id"));
      Set<UUID> held = viewer.signedIn() ? deals.heldCardProducts() : Set.of();
      long reference = Savings.total(d.components(), d.referenceSpendPaise());
      DealRanking.CardMatch match = DealRanking.match(d.eligibleCardProducts(), held);
      // alone, the deal is its own maximum: savingsNorm is full whenever it saves anything
      DealRanking.Ranked r = new DealRanking.Ranked(new DealRanking.Candidate(d.id(), d.priority(), reference, match,
          d.sponsored(), d.sponsorWeight()), DealRanking.score(match, reference > 0 ? DealRanking.FULL : 0,
          d.sponsored(), d.sponsorWeight()));
      Map<UUID, String> names = deals.publishedCardNames(d.eligibleCardProducts());
      long spend = spendPaise == null ? d.referenceSpendPaise() : spendPaise;
      Savings.Breakdown b = Savings.of(d.components(), spend);
      List<SavingLine> lines = b.lines().stream()
          .map(l -> new SavingLine(Component.of(l.component()), l.savingPaise())).toList();
      return new DealDetailView(view(d, r, names, viewer), spend, lines, b.totalPaise());
    });
  }

  private DealView view(Deal d, DealRanking.Ranked r, Map<UUID, String> names, Viewer viewer) {
    List<EligibleCard> cards = d.eligibleCardProducts().stream().map(c -> new EligibleCard(c, names.get(c))).toList();
    return new DealView(d.id(), d.merchantId(), d.merchantName(), d.title(), d.description(), d.categoryPath(),
        d.components().stream().map(Component::of).toList(), cards, d.referenceSpendPaise(),
        r.candidate().savingsPaise(), r.candidate().match().name().toLowerCase(java.util.Locale.ROOT), d.sponsored(),
        r.scoreBps(), d.validFrom(), d.validTo(), links.deal(d.id(), viewer.accountId()));
  }

  static @Nullable String category(@Nullable String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String c = raw.strip().toLowerCase(java.util.Locale.ROOT);
    if (!CATEGORY.matcher(c).matches()) {
      throw ValidationException.of("category", "CATEGORY_INVALID", "an ltree path such as shopping.online");
    }
    return c;
  }

  private static DealRanking.Position position(CursorPosition c) {
    List<String> k = c.keys();
    if (k.size() != 3) {
      throw new DexterException(CoreErrors.CURSOR_INVALID, "Invalid cursor");
    }
    try {
      return new DealRanking.Position(Integer.parseInt(k.get(0)), Integer.parseInt(k.get(1)),
          UUID.fromString(k.get(2)));
    } catch (IllegalArgumentException e) {
      throw new DexterException(CoreErrors.CURSOR_INVALID, "Invalid cursor");
    }
  }
}
