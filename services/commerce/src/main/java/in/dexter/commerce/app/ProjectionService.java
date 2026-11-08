package in.dexter.commerce.app;

import in.dexter.contracts.events.billing.EntitlementsChangedV1;
import in.dexter.contracts.events.catalog.CardPublishedV1;
import in.dexter.contracts.events.portfolio.CardChangedV1;
import in.dexter.core.tenant.Tenant;
import in.dexter.db.tenant.TenantTx;
import in.dexter.events.EventEnvelope;
import in.dexter.events.InboxGuard;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;

/**
 * Commerce's three projections (MASTER_PLAN 6.2 / 6.4; each consumer has its inbox and version guard):
 * <ul>
 *   <li>{@code catalog-cards} on the compacted {@code dexter.catalog.card.v1} — which card products exist
 *       (applications may only name a published one; the feed names a deal's eligible cards). Global,
 *       PROJECTOR-written.</li>
 *   <li>{@code held-cards} on {@code dexter.portfolio.card.v1} — the viewer's cards, for card-match.</li>
 *   <li>{@code ad-free} on {@code dexter.billing.entitlements.v1} — the {@code adFree} entitlement, for ads.</li>
 * </ul>
 */
public final class ProjectionService {
  public static final String CATALOG_CONSUMER = "catalog-cards";
  public static final String CARD_CONSUMER = "held-cards";
  public static final String ENTITLEMENTS_CONSUMER = "ad-free";

  public enum Outcome { APPLIED, STALE, DUPLICATE, IGNORED }

  private final TenantTx tx;
  private final InboxGuard inbox;
  private final ProjectionStore store;
  private final JsonMapper mapper;

  public ProjectionService(TenantTx tx, InboxGuard inbox, ProjectionStore store, JsonMapper mapper) {
    this.tx = tx;
    this.inbox = inbox;
    this.store = store;
    this.mapper = mapper;
  }

  public Outcome onCatalogEvent(EventEnvelope e) {
    if (!e.type().equals(CardPublishedV1.PUBLISHED) && !e.type().equals(CardPublishedV1.RETIRED)) {
      return Outcome.IGNORED;
    }
    CardPublishedV1 p = mapper.treeToValue(e.data(), CardPublishedV1.class);
    Map<String, Object> s = p.snapshot() == null ? Map.of() : p.snapshot();
    String status = "retired".equals(p.status()) ? "retired" : "published";
    return tx.write(CommerceTenants.PROJECTOR, () -> {
      if (!inbox.firstDelivery(CATALOG_CONSUMER, e.id())) {
        return Outcome.DUPLICATE;
      }
      return store.upsertCardProduct(p.cardId(), p.slug(), text(s, "name", p.slug()), text(s, "issuerCode", ""),
          text(s, "network", ""), status, e.aggregateVersion()) ? Outcome.APPLIED : Outcome.STALE;
    });
  }

  public Outcome onCardEvent(EventEnvelope e) {
    if (!e.type().startsWith("portfolio.card.") || e.accountId() == null) {
      return Outcome.IGNORED;
    }
    CardChangedV1 c = mapper.treeToValue(e.data(), CardChangedV1.class);
    UUID account = e.accountId();
    return tx.write(Tenant.user(account), () -> {
      if (!inbox.firstDelivery(CARD_CONSUMER, e.id())) {
        return Outcome.DUPLICATE;
      }
      boolean applied = "removed".equals(c.status())
          ? store.deleteHeldCard(account, c.userCardId(), e.aggregateVersion())
          : store.upsertHeldCard(account, c.userCardId(), c.cardProductId(), e.aggregateVersion());
      return applied ? Outcome.APPLIED : Outcome.STALE;
    });
  }

  public Outcome onEntitlementsEvent(EventEnvelope e) {
    if (!e.type().equals(EntitlementsChangedV1.TYPE) || e.accountId() == null) {
      return Outcome.IGNORED;
    }
    EntitlementsChangedV1 c = mapper.treeToValue(e.data(), EntitlementsChangedV1.class);
    UUID account = e.accountId();
    boolean adFree = c.entitlements() != null && Boolean.TRUE.equals(c.entitlements().get("adFree"));
    return tx.write(Tenant.user(account), () -> {
      if (!inbox.firstDelivery(ENTITLEMENTS_CONSUMER, e.id())) {
        return Outcome.DUPLICATE;
      }
      return store.upsertAdFree(account, c.tier(), adFree, c.version()) ? Outcome.APPLIED : Outcome.STALE;
    });
  }

  private static String text(Map<String, Object> s, String key, String fallback) {
    Object v = s.get(key);
    return v == null ? fallback : String.valueOf(v);
  }
}
