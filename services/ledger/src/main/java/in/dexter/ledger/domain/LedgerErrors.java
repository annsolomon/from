package in.dexter.ledger.domain;

import in.dexter.core.error.ErrorCode;

/** Ledger error codes (docs/api/errors.md, ledger section). */
public enum LedgerErrors implements ErrorCode {
  /** Another expense of this account already has the client reference (import de-duplication). */
  CLIENT_REF_TAKEN(409, "Client reference already used"),
  /** A custom category with this path already exists for the account (or it is a system path). */
  CATEGORY_EXISTS(409, "Category already exists"),
  /** Admin-console tokens cannot act as an end-user account. */
  USER_TOKEN_REQUIRED(403, "User token required"),
  /** {@code POST /v1/imports/{id}:start} on a job that is completed or failed (start again = a new import). */
  IMPORT_NOT_STARTABLE(409, "Import cannot be started"),
  /** The account already has the maximum number of categorisation rules. */
  RULE_LIMIT_REACHED(409, "Rule limit reached");

  private final int status;
  private final String title;

  LedgerErrors(int status, String title) {
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
