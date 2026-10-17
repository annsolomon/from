package in.dexter.identity.domain;

import java.util.Locale;

/** {@code identity.accounts.status} (MASTER_PLAN P04). */
public enum AccountStatus {
  PENDING_VERIFICATION, ACTIVE, LOCKED, DISABLED, DELETION_PENDING, DELETED;

  public String db() {
    return name().toLowerCase(Locale.ROOT);
  }

  public static AccountStatus fromDb(String s) {
    return valueOf(s.toUpperCase(Locale.ROOT));
  }

  /** Can sign in (given the right password). */
  public boolean canSignIn() {
    return this == ACTIVE;
  }
}
