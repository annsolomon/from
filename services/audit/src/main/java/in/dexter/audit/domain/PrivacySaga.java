package in.dexter.audit.domain;

import java.util.List;

/**
 * The privacy saga's phases (MASTER_PLAN P18, ADR-0024 §3). Delete: identity freezes the account (deletion_pending,
 * sessions revoked) → every service erases or anonymizes → identity shreds the account's data key and tombstones
 * the account → audit erases its own rows and keeps a pseudonymous record. Export: every service writes its part →
 * audit zips them with its own. A phase asked of services ({@link #remote}) waits for every acknowledgement; a
 * local phase runs in the audit service.
 */
public final class PrivacySaga {
  public static final String EXPORT = "export";
  public static final String DELETE = "delete";
  public static final String FREEZE = "freeze";
  public static final String ERASE = "erase";
  public static final String SHRED = "shred";
  public static final String LOCAL = "local";
  public static final String ARCHIVE = "archive";
  public static final String DONE = "done";
  public static final String IDENTITY = "identity";

  private PrivacySaga() {}

  public static String firstPhase(String kind) {
    return switch (kind) {
      case DELETE -> FREEZE;
      case EXPORT -> EXPORT;
      default -> throw new IllegalArgumentException("unknown kind: " + kind);
    };
  }

  /** The phase after {@code phase} of a {@code kind} request ({@link #DONE} at the end). */
  public static String next(String kind, String phase) {
    if (DELETE.equals(kind)) {
      return switch (phase) {
        case FREEZE -> ERASE;
        case ERASE -> SHRED;
        case SHRED -> LOCAL;
        case LOCAL -> DONE;
        default -> throw new IllegalArgumentException("not a delete phase: " + phase);
      };
    }
    return switch (phase) {
      case EXPORT -> ARCHIVE;
      case ARCHIVE -> DONE;
      default -> throw new IllegalArgumentException("not an export phase: " + phase);
    };
  }

  /** True when the phase is asked of other services (and waits for their acknowledgements). */
  public static boolean remote(String phase) {
    return FREEZE.equals(phase) || ERASE.equals(phase) || SHRED.equals(phase) || EXPORT.equals(phase);
  }

  /** {@code phase}, or the first phase after it that has someone to run it (a remote phase nobody runs is skipped). */
  public static String firstActionable(String kind, String phase, List<String> participants) {
    String p = phase;
    while (remote(p) && servicesFor(p, participants).isEmpty()) {
      p = next(kind, p);
    }
    return p;
  }

  /**
   * Whether an acknowledgement completes the service's part: an erase must leave nothing (identity's account row and
   * key go at shred, so its erase is done as answered), a shred must leave nothing, freeze and export are done.
   */
  public static boolean done(String phase, String service, long remaining) {
    return switch (phase) {
      case ERASE -> IDENTITY.equals(service) || remaining == 0;
      case SHRED -> remaining == 0;
      default -> true;
    };
  }

  /** The services a remote phase is asked of: identity alone freezes and shreds; every participant otherwise. */
  public static List<String> servicesFor(String phase, List<String> participants) {
    if (FREEZE.equals(phase) || SHRED.equals(phase)) {
      return participants.contains(IDENTITY) ? List.of(IDENTITY) : List.of();
    }
    return List.copyOf(participants);
  }
}
