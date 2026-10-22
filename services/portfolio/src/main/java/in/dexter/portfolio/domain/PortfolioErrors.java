package in.dexter.portfolio.domain;

import in.dexter.core.error.ErrorCode;

/** Portfolio error codes (docs/api/errors.md, portfolio section). */
public enum PortfolioErrors implements ErrorCode {
  /** Admin-console tokens cannot act as an end-user account. */
  USER_TOKEN_REQUIRED(403, "User token required"),
  /** The account already holds this card product with these last four digits. */
  CARD_EXISTS(409, "Card already added"),
  /** Transactions, EMI plans and rewards need an active card (closed or removed cards are read-only). */
  CARD_NOT_ACTIVE(409, "Card is not active"),
  /** Another transaction of this account already has the client reference. */
  CLIENT_REF_TAKEN(409, "Client reference already used"),
  /** The suggestion was already confirmed or rejected, or its transaction/expense is linked elsewhere. */
  LINK_DECIDED(409, "Link already decided"),
  /** The EMI plan is closed or foreclosed. */
  EMI_NOT_ACTIVE(409, "EMI plan is not active"),
  /** A redemption larger than the card's available points. */
  INSUFFICIENT_POINTS(409, "Not enough points");

  private final int status;
  private final String title;

  PortfolioErrors(int status, String title) {
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
