package in.dexter.contracts.events.portfolio;

import java.util.UUID;

/**
 * {@code portfolio.link.confirmed} v1 (MASTER_PLAN P11) on {@code dexter.portfolio.link.v1} (key = account id): the
 * user confirmed that a ledger expense and a card transaction are the same purchase.
 */
public record LinkConfirmedV1(UUID linkId, UUID expenseId, UUID transactionId, UUID userCardId, String method,
    String confirmedAt) {
  public static final String TYPE = "portfolio.link.confirmed";
  public static final int VERSION = 1;
  public static final String TOPIC = "dexter.portfolio.link.v1";
}
