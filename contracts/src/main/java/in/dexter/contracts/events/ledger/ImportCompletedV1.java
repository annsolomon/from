package in.dexter.contracts.events.ledger;

import java.util.UUID;

/**
 * {@code ledger.import.completed} v1 (MASTER_PLAN P08): a CSV import job finished, successfully ({@code status}
 * {@code completed}: rows merged, duplicates skipped, invalid rows counted) or not ({@code failed} with
 * {@code errorCode}). Each created expense also has its own {@code ledger.expense.created} event. Envelope
 * accountId = the importing account; aggregate = the job.
 */
public record ImportCompletedV1(UUID jobId, String status, int total, int ok, int duplicates, int failed,
    String errorCode, String finishedAt) {
  public static final String TYPE = "ledger.import.completed";
  public static final int VERSION = 1;
  public static final String TOPIC = "dexter.ledger.import.v1";
}
