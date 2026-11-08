package in.dexter.commerce.domain;

import java.util.Locale;

/**
 * A card application's life (MASTER_PLAN P17). The user submits and may withdraw while it is open; an operator
 * moves it to in_review and decides. Decided and withdrawn are final.
 */
public enum ApplicationStatus {
  SUBMITTED, IN_REVIEW, APPROVED, REJECTED, WITHDRAWN;

  public String db() {
    return name().toLowerCase(Locale.ROOT);
  }

  public static ApplicationStatus ofDb(String s) {
    return valueOf(s.toUpperCase(Locale.ROOT));
  }

  public boolean isOpen() {
    return this == SUBMITTED || this == IN_REVIEW;
  }

  /** What an operator's decision may move this status to. */
  public boolean operatorMayMoveTo(ApplicationStatus next) {
    return switch (this) {
      case SUBMITTED -> next == IN_REVIEW || next == APPROVED || next == REJECTED;
      case IN_REVIEW -> next == APPROVED || next == REJECTED;
      default -> false;
    };
  }

  public boolean isDecision() {
    return this == APPROVED || this == REJECTED;
  }
}
