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
  INSUFFICIENT_POINTS(409, "Not enough points"),
  /** P12: submit before the file was PUT to the upload URL (or the URL expired before the upload). */
  STATEMENT_NOT_UPLOADED(409, "Statement file not uploaded"),
  /** P12: the scan refused the file (empty, too large, not a PDF); the upload is failed with that code. */
  STATEMENT_FILE_REJECTED(422, "Statement file rejected"),
  /** P12: this file was already uploaded for this card and is being processed or was confirmed. */
  STATEMENT_ALREADY_UPLOADED(409, "Statement already uploaded"),
  /** P12: the upload is not in a state that allows this action (e.g. submit twice with different keys). */
  STATEMENT_WRONG_STATE(409, "Statement upload is in another state"),
  /** P12: the statement's card number (last four digits) is not the card it was uploaded for. */
  STATEMENT_CARD_MISMATCH(409, "Statement belongs to another card"),
  /** P12: the object store did not answer; nothing was changed, retry later. */
  OBJECT_STORE_UNAVAILABLE(503, "Object store unavailable");

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
