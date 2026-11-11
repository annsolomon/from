package in.dexter.audit.app;

import in.dexter.db.page.Page;
import in.dexter.db.page.PageRequest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** The privacy saga's requests, and audit's own part (the same SQL functions every participant has). */
public interface PrivacyStore {
  record Request(UUID id, @Nullable UUID accountId, @Nullable String subjectRef, String kind, String status,
      String phase, int attempt, List<String> services, String stepsJson, Instant phaseStartedAt,
      @Nullable Instant nextCheckAt, @Nullable String archiveKey, @Nullable Long archiveBytes, @Nullable String error,
      Instant createdAt, @Nullable Instant completedAt, int version) {}

  /** False when a request of this kind is already running for the account. */
  boolean insert(Request r);

  Optional<Request> find(UUID id);

  /** FOR UPDATE (the saga's single writer per request). */
  Optional<Request> lock(UUID id);

  Page<Request> list(PageRequest page);

  /** Running requests whose check is due, locked, skipping those another instance holds. */
  List<UUID> due(Instant now, int limit);

  void update(Request r);

  /** The account's requests keep only {@code subjectRef} (a completed deletion). */
  int pseudonymize(UUID account, String subjectRef);

  long eraseLocal(UUID account);

  long remainingLocal(UUID account);

  String exportLocal(UUID account);
}
