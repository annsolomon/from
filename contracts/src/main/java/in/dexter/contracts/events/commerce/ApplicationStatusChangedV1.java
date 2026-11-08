package in.dexter.contracts.events.commerce;

import java.util.UUID;

/**
 * {@code commerce.application.status-changed} v1 (MASTER_PLAN P17; P18 notifies on it): a card application was
 * submitted, moved to review, approved, rejected or withdrawn. {@code previousStatus} is null for the submission;
 * {@code reason} is the operator's (or null). Key = the applicant's account id.
 */
public record ApplicationStatusChangedV1(UUID applicationId, UUID cardProductId, String status,
    String previousStatus, String reason, long version, String changedAt) {
  public static final String TYPE = "commerce.application.status-changed";
  public static final int VERSION = 1;
  public static final String TOPIC = "dexter.commerce.application.v1";
}
