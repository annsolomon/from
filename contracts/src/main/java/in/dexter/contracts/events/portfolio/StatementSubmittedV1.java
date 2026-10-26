package in.dexter.contracts.events.portfolio;

import java.util.UUID;

/**
 * {@code portfolio.statement.submitted} v1 (MASTER_PLAN P12) on {@code dexter.portfolio.statement.v1} (key = account
 * id): an uploaded statement passed the scan and waits for the ai-parser. {@code fileUrl} is a presigned GET on the
 * internal object-store endpoint (valid until {@code urlExpiresAt}); the parser checks the bytes against
 * {@code fileSha256} (hex). {@code last4} is the card's, so the review can flag a statement of another card.
 */
public record StatementSubmittedV1(UUID uploadId, UUID userCardId, String last4, String fileUrl, String fileSha256,
    long sizeBytes, String contentType, String urlExpiresAt, String submittedAt) {
  public static final String TYPE = "portfolio.statement.submitted";
  public static final int VERSION = 1;
  public static final String TOPIC = "dexter.portfolio.statement.v1";
}
