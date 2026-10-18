package in.dexter.catalog.domain;

import in.dexter.core.error.ErrorCode;

/** Catalog error codes (docs/api/errors.md, catalog section). */
public enum CatalogErrors implements ErrorCode {
  /** Another card product already uses this slug. */
  SLUG_TAKEN(409, "Slug already used"),
  /** Another issuer already uses this code. */
  ISSUER_EXISTS(409, "Issuer already exists"),
  /** A retired card cannot be edited or published again. */
  CARD_RETIRED(409, "Card product is retired"),
  /** The offer is already withdrawn. */
  OFFER_WITHDRAWN(409, "Offer already withdrawn"),
  /** Admin endpoints need an admin-console token with a catalog role. */
  ADMIN_TOKEN_REQUIRED(403, "Admin token required"),
  /** P10: another live source already uses this URL. */
  SOURCE_EXISTS(409, "Source already exists"),
  /** P10: the review item was already approved or rejected (an approve of an approved item answers its promotion). */
  REVIEW_DECIDED(409, "Review item already decided"),
  /** P10: the card changed since the diff was made (or a newer item superseded it); approve the newer item. */
  REVIEW_STALE(409, "Review item is stale"),
  /** P10: the card has unpublished draft edits; publish or discard the draft before approving a review item. */
  DRAFT_PENDING(409, "Card has unpublished draft changes"),
  /** P10: a card that was never published has only its draft; it cannot be discarded. */
  DRAFT_NOT_DISCARDABLE(409, "Draft cannot be discarded");

  private final int status;
  private final String title;

  CatalogErrors(int status, String title) {
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
