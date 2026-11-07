package in.dexter.billing.domain;

import in.dexter.core.error.ErrorCode;

/** Billing error codes (docs/api/errors.md, billing section). */
public enum BillingErrors implements ErrorCode {
  /** Admin-console tokens cannot act as a user, as on every other user endpoint. */
  USER_TOKEN_REQUIRED(403, "User token required"),
  /** No plan with that code. */
  PLAN_NOT_FOUND(404, "Plan not found"),
  /** FREE (or a retired plan) cannot be bought. */
  PLAN_NOT_PURCHASABLE(422, "Plan cannot be purchased"),
  /** The account already has a live subscription (incomplete, trialing, active or past due). */
  SUBSCRIPTION_EXISTS(409, "A subscription already exists"),
  /** The account has never had a subscription. */
  SUBSCRIPTION_NOT_FOUND(404, "No subscription"),
  /** Only a trialing, active or past-due subscription can be canceled. */
  SUBSCRIPTION_NOT_CANCELABLE(409, "Subscription cannot be canceled"),
  /** The provider declined the charge (the checkout's stored response carries this code). */
  PAYMENT_DECLINED(402, "Payment declined"),
  /** The checkout never reached the provider and was closed by the recovery sweep. */
  PAYMENT_NOT_COMPLETED(402, "Payment not completed"),
  /** The provider could not be reached; the attempt is kept and finished by a retry or the sweeper. */
  PROVIDER_UNAVAILABLE(503, "Payment provider unavailable"),
  /** No payment provider of that name. */
  PROVIDER_UNKNOWN(404, "Unknown payment provider"),
  /** A webhook whose signature is missing, malformed, stale or wrong. */
  WEBHOOK_SIGNATURE_INVALID(400, "Webhook signature invalid"),
  /** A signed webhook whose body is not an event the adapter understands. */
  WEBHOOK_INVALID(400, "Webhook payload invalid");

  private final int status;
  private final String title;

  BillingErrors(int status, String title) {
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
