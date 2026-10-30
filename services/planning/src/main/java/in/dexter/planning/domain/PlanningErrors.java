package in.dexter.planning.domain;

import in.dexter.core.error.ErrorCode;

/** Planning error codes (docs/api/errors.md, planning section). */
public enum PlanningErrors implements ErrorCode {
  /** Admin-console tokens cannot act as a user, as on every other user endpoint. */
  USER_TOKEN_REQUIRED(403, "User token required"),
  /** Only an active budget can be changed; an ended one is read-only. */
  BUDGET_NOT_ACTIVE(409, "Budget is not active"),
  /** Only an active liability can be changed or closed. */
  LIABILITY_NOT_ACTIVE(409, "Liability is not active"),
  /** An adjustment the scenario evaluator does not know, or one missing a field it needs. */
  SCENARIO_ADJUSTMENT_INVALID(422, "Scenario adjustment is not valid");

  private final int status;
  private final String title;

  PlanningErrors(int status, String title) {
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
