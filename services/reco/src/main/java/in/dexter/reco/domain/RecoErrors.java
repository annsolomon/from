package in.dexter.reco.domain;

import in.dexter.core.error.ErrorCode;

/** Reco error codes (docs/api/errors.md, reco section). */
public enum RecoErrors implements ErrorCode {
  /** Admin-console tokens cannot act as a user, as on every other user endpoint. */
  USER_TOKEN_REQUIRED(403, "User token required"),
  /** {@code /explain/{cardProductId}} for a product the catalog projection does not have as published. */
  CARD_NOT_IN_CATALOG(404, "Card is not in the catalog"),
  /** best-card-now without a positive {@code amountPaise}: there is no purchase to rank cards for. */
  AMOUNT_REQUIRED(400, "A positive amountPaise is required"),
  /** best-card-now with neither {@code merchantId} nor {@code category}: nothing to match a rule against. */
  MERCHANT_OR_CATEGORY_REQUIRED(400, "Give merchantId or category"),
  /** A stream ticket that has expired, was already used, or never existed (P15 SSE). */
  STREAM_TICKET_INVALID(401, "Stream ticket is not valid");

  private final int status;
  private final String title;

  RecoErrors(int status, String title) {
    this.status = status;
    this.title = title;
  }

  @Override
  public String code() {
    return name();
  }

  @Override
  public int status() {
    return status;
  }

  @Override
  public String title() {
    return title;
  }
}
