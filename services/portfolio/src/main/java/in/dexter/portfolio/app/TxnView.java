package in.dexter.portfolio.app;

import java.time.Instant;
import java.util.UUID;

/** API representation of a card transaction (and, on create, what it earned). */
public record TxnView(UUID id, UUID userCardId, UUID statementId, Instant postedAt, long amountPaise, String direction,
    String kind, String description, UUID merchantId, String categoryPath, UUID expenseId, String clientRef,
    String source, Instant createdAt, Reward reward) {

  /** What the transaction earned on the card's catalog terms ({@code type} none = nothing). */
  public record Reward(String type, int rateBps, long valuePaise, long points) {}

  static TxnView of(TransactionStore.TxnRow t, Reward r) {
    return new TxnView(t.id(), t.userCardId(), t.statementId(), t.postedAt(), t.amountPaise(), t.direction(), t.kind(),
        t.description(), t.merchantId(), t.categoryPath(), t.expenseId(), t.clientRef(), t.source(), t.createdAt(), r);
  }
}
