package in.dexter.catalog.app;

import in.dexter.contracts.events.catalog.CardPublishedV1;
import in.dexter.contracts.events.catalog.OfferChangedV1;
import in.dexter.events.EventType;

/** Catalog's event types (contracts/events/catalog.*.v1.json). */
public final class CatalogEvents {
  public static final EventType<CardPublishedV1> CARD_PUBLISHED =
      EventType.of("catalog", "card", "published", CardPublishedV1.VERSION, CardPublishedV1.class);
  public static final EventType<CardPublishedV1> CARD_RETIRED =
      EventType.of("catalog", "card", "retired", CardPublishedV1.VERSION, CardPublishedV1.class);
  public static final EventType<OfferChangedV1> OFFER_CHANGED =
      EventType.of("catalog", "offer", "changed", OfferChangedV1.VERSION, OfferChangedV1.class);

  private CatalogEvents() {}
}
