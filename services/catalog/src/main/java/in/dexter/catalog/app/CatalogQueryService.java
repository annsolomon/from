package in.dexter.catalog.app;

import in.dexter.catalog.app.CatalogStore.Offer;
import in.dexter.catalog.app.CatalogStore.Potential;
import in.dexter.catalog.app.CatalogStore.PublishedCard;
import in.dexter.catalog.app.CatalogStore.SearchHit;
import in.dexter.catalog.domain.CardDocuments;
import in.dexter.core.error.FieldError;
import in.dexter.core.error.NotFoundException;
import in.dexter.core.error.ValidationException;
import in.dexter.core.tenant.Tenant;
import in.dexter.db.page.Page;
import in.dexter.db.page.PageRequest;
import in.dexter.db.tenant.TenantTx;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The public catalog (MASTER_PLAN P09): no token, the app pool sees published data only (RLS). Cards are served as
 * their current published snapshot plus {@code version}: the list, the detail and the {@code catalog.card.published}
 * event carry the same document.
 */
public final class CatalogQueryService {
  /** Public reads run without an account; the RLS policies of catalog_app do not depend on one. */
  public static final Tenant PUBLIC = new Tenant(new UUID(0, 0), "PUBLIC", null);
  public static final int SEARCH_LIMIT = 20;
  public static final int POTENTIAL_LIMIT = 10;
  public static final int OFFER_LIMIT = 100;
  static final Pattern PATH = Pattern.compile("[a-z][a-z0-9_]{0,31}(\\.[a-z][a-z0-9_]{0,31}){0,3}");

  public record CardView(int version, ObjectNode card) {}

  public record PotentialView(String category, List<Potential> cards) {}

  private final TenantTx tx;
  private final CatalogStore store;
  private final ObjectMapper mapper;
  private final Clock clock;

  public CatalogQueryService(TenantTx tx, CatalogStore store, ObjectMapper mapper, Clock clock) {
    this.tx = tx;
    this.store = store;
    this.mapper = mapper;
    this.clock = clock;
  }

  public Page<ObjectNode> list(PageRequest page, CardFilter f) {
    List<FieldError> errors = new ArrayList<>();
    if (f.network() != null && !CardDocuments.NETWORKS.contains(f.network())) {
      errors.add(new FieldError("network", "INVALID", "visa, mastercard, rupay, amex or diners"));
    }
    if (f.maxAnnualFee() != null && f.maxAnnualFee() < 0) {
      errors.add(new FieldError("maxAnnualFee", "INVALID", "Paise, not negative"));
    }
    if (f.category() != null && !PATH.matcher(f.category()).matches()) {
      errors.add(new FieldError("category", "INVALID", "A category path"));
    }
    if (f.minRateBps() != null && (f.category() == null || f.minRateBps() < 0 || f.minRateBps() > 10_000)) {
      errors.add(new FieldError("minRateBps", "INVALID", "0-10000, together with category"));
    }
    if (!errors.isEmpty()) {
      throw new ValidationException(errors);
    }
    return tx.read(PUBLIC, () -> store.list(page, f)).map(this::view);
  }

  public CardView bySlug(String slug) {
    PublishedCard c = tx.read(PUBLIC, () -> store.bySlug(slug)).orElseThrow(() -> new NotFoundException("card"));
    return new CardView(c.version(), view(c));
  }

  public List<SearchHit> search(String q) {
    String text = q == null ? "" : q.strip().toLowerCase(Locale.ROOT);
    if (text.length() < 2 || text.length() > 60) {
      throw new ValidationException(List.of(new FieldError("q", "INVALID", "2-60 characters")));
    }
    return tx.read(PUBLIC, () -> store.search(text, SEARCH_LIMIT));
  }

  public PotentialView potential(String category) {
    if (category == null || !PATH.matcher(category).matches()) {
      throw new ValidationException(List.of(new FieldError("category", "INVALID", "A category path")));
    }
    return new PotentialView(category, tx.read(PUBLIC, () -> store.potential(category, POTENTIAL_LIMIT)));
  }

  public List<Offer> offers(UUID merchantId, String category) {
    if (category != null && !PATH.matcher(category).matches()) {
      throw new ValidationException(List.of(new FieldError("category", "INVALID", "A category path")));
    }
    return tx.read(PUBLIC, () -> store.activeOffers(merchantId, category, clock.instant(), OFFER_LIMIT));
  }

  private ObjectNode view(PublishedCard c) {
    ObjectNode n = (ObjectNode) mapper.readTree(c.snapshotJson());
    n.put("version", c.version());
    return n;
  }
}
