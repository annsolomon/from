package in.dexter.commerce.domain;

import in.dexter.core.error.ErrorCode;

/** Commerce error codes (docs/api/errors.md, the commerce section). */
public enum CommerceErrors implements ErrorCode {
  /** Admin-console tokens cannot act as a user on the user endpoints. */
  USER_TOKEN_REQUIRED(403, "User token required"),
  /** /v1/admin/commerce/** takes an admin token holding an operator role. */
  ADMIN_TOKEN_REQUIRED(403, "Admin token required"),
  DEAL_NOT_FOUND(404, "Deal not found"),
  CLICK_TOKEN_INVALID(400, "Invalid click link"),
  CLICK_TOKEN_EXPIRED(410, "This link has expired"),
  DEAL_UNAVAILABLE(410, "This deal is no longer available"),
  AD_NOT_FOUND(410, "This ad is no longer available"),
  NETWORK_UNKNOWN(404, "Unknown affiliate network"),
  POSTBACK_SIGNATURE_INVALID(400, "Postback signature invalid"),
  APPLICATION_NOT_FOUND(404, "Card application not found"),
  APPLICATION_EXISTS(409, "An open application for this card already exists"),
  APPLICATION_NOT_OPEN(409, "This application is already decided or withdrawn"),
  APPLICATION_TRANSITION_INVALID(409, "This decision is not allowed from the application's status"),
  PLACEMENT_UNKNOWN(400, "Unknown ad placement"),
  CAMPAIGN_NOT_FOUND(404, "Campaign not found"),
  CREATIVE_NOT_FOUND(404, "Creative not found"),
  STATS_RANGE_INVALID(400, "Invalid stats range");

  private final int status;
  private final String title;

  CommerceErrors(int status, String title) {
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
