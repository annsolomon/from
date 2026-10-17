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
  ADMIN_TOKEN_REQUIRED(403, "Admin token required");

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
