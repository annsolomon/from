package in.dexter.contracts.events.portfolio;

import java.util.UUID;

/**
 * {@code portfolio.transaction.recorded} v1 (MASTER_PLAN P11) on {@code dexter.portfolio.transaction.v1} (key =
 * account id): a card transaction, its statement, and what it earned on the card's catalog terms
 * ({@code rewardType} {@code none} when nothing).
 */
public record TransactionRecordedV1(UUID transactionId, UUID userCardId, UUID statementId, String postedAt,
    long amountPaise, String direction, String kind, UUID merchantId, String categoryPath, String rewardType,
    long rewardValuePaise, long points, String recordedAt) {
  public static final String TYPE = "portfolio.transaction.recorded";
  public static final int VERSION = 1;
  public static final String TOPIC = "dexter.portfolio.transaction.v1";
}
