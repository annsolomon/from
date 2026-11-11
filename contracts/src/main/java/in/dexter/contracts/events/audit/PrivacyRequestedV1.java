package in.dexter.contracts.events.audit;

import java.util.List;
import java.util.UUID;

/**
 * {@code audit.privacy.requested} v1 (MASTER_PLAN P18, ADR-0024): the privacy saga asks the listed {@code services}
 * to run one {@code phase} of a request for the envelope's account. {@code kind} is {@code export} or {@code delete};
 * {@code phase} is {@code freeze} / {@code erase} / {@code shred} (delete) or {@code export}. {@code attempt} counts
 * from 1; a phase that timed out is asked again with the next attempt, to the services that have not answered.
 * Each participant answers with {@code <svc>.privacy.completed} ({@link
 * in.dexter.contracts.events.privacy.PrivacyCompletedV1}). Key = the account id.
 */
public record PrivacyRequestedV1(UUID requestId, String kind, String phase, int attempt, List<String> services,
    String requestedAt) {
  public static final String TYPE = "audit.privacy.requested";
  public static final int VERSION = 1;
  public static final String TOPIC = "dexter.audit.privacy.v1";

  public static final String EXPORT = "export";
  public static final String DELETE = "delete";
  public static final String FREEZE = "freeze";
  public static final String ERASE = "erase";
  public static final String SHRED = "shred";
}
