package in.dexter.commerce.app;

import in.dexter.commerce.domain.ApplicationStatus;
import in.dexter.db.page.Page;
import in.dexter.db.page.PageRequest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Card applications. Infra implements it; the app pool sees the caller's rows, the admin pool every row. */
public interface ApplicationStore {
  record HistoryEntry(String status, Instant at, @Nullable UUID by, @Nullable String reason) {}

  record Application(UUID id, UUID accountId, UUID cardProductId, ApplicationStatus status,
      List<HistoryEntry> history, @Nullable String reason, int version, Instant createdAt, Instant updatedAt,
      @Nullable Instant decidedAt, @Nullable UUID decidedBy) {
    public Application {
      history = List.copyOf(history);
    }
  }

  /** False when an open application for the same card exists (the partial unique index). */
  boolean insert(Application a);

  Optional<Application> find(UUID id);

  Optional<Application> lock(UUID id);

  /** Compare-and-set on version: appends {@code entry} to the history. False when the version moved. */
  boolean transition(UUID id, int expectedVersion, ApplicationStatus next, HistoryEntry entry,
      @Nullable String reason, @Nullable Instant decidedAt, @Nullable UUID decidedBy);

  /** The caller's applications, newest first. */
  Page<Application> list(PageRequest page);

  /** Operators: open applications, oldest first. */
  Page<Application> queue(PageRequest page);
}
