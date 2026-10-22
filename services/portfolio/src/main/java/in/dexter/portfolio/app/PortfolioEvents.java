package in.dexter.portfolio.app;

import in.dexter.contracts.events.portfolio.CardChangedV1;
import in.dexter.contracts.events.portfolio.LinkConfirmedV1;
import in.dexter.contracts.events.portfolio.TransactionRecordedV1;
import in.dexter.events.EventType;
import java.util.List;

/** Portfolio's event types (contracts/events/portfolio.*.v1.json). */
public final class PortfolioEvents {
  public static final EventType<CardChangedV1> CARD_ADDED =
      EventType.of("portfolio", "card", "added", CardChangedV1.VERSION, CardChangedV1.class);
  public static final EventType<CardChangedV1> CARD_UPDATED =
      EventType.of("portfolio", "card", "updated", CardChangedV1.VERSION, CardChangedV1.class);
  public static final EventType<CardChangedV1> CARD_REMOVED =
      EventType.of("portfolio", "card", "removed", CardChangedV1.VERSION, CardChangedV1.class);
  public static final EventType<TransactionRecordedV1> TRANSACTION_RECORDED =
      EventType.of("portfolio", "transaction", "recorded", TransactionRecordedV1.VERSION, TransactionRecordedV1.class);
  public static final EventType<LinkConfirmedV1> LINK_CONFIRMED =
      EventType.of("portfolio", "link", "confirmed", LinkConfirmedV1.VERSION, LinkConfirmedV1.class);

  /** The aggregates portfolio publishes (topics dexter.portfolio.&lt;aggregate&gt;.v1, created by the relay). */
  public static final List<String> AGGREGATES = List.of("card", "transaction", "link");

  private PortfolioEvents() {}
}
