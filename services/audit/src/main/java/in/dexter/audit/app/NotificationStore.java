package in.dexter.audit.app;

import in.dexter.audit.domain.NotificationRules.Prefs;
import in.dexter.db.page.Page;
import in.dexter.db.page.PageRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Notifications (in-app inbox and the email / push deliveries) and the per-account preferences. */
public interface NotificationStore {
  record Notification(UUID id, UUID accountId, String channel, String category, String template,
      Map<String, Object> payload, String status, int attempts, @Nullable Instant nextAttemptAt,
      @Nullable String lastError, UUID sourceEventId, Instant createdAt, @Nullable Instant sentAt,
      @Nullable Instant readAt, int version) {}

  /** False when this event already produced a notification on this channel (a redelivery). */
  boolean insert(Notification n);

  Page<Notification> inbox(PageRequest page, boolean unreadOnly);

  long unread();

  Optional<Notification> find(UUID id);

  /** Marks one in-app notification read (the caller's own); false when it is not theirs or not in-app. */
  boolean markRead(UUID id, Instant at);

  int markAllRead(Instant at);

  Optional<Prefs> prefs(UUID account);

  /** Insert (expected version 0, no row yet) or update at {@code expectedVersion}; false on a version mismatch. */
  boolean savePrefs(UUID account, Prefs prefs, int expectedVersion, Instant at);

  /** Claims due email / push deliveries across accounts (SKIP LOCKED) and leases them until {@code leaseUntil}. */
  List<Notification> claimDue(Instant now, int limit, Instant leaseUntil);

  void delivered(UUID id, int attempts, Instant at);

  /** A failed attempt: retried at {@code nextAttemptAt}, or failed for good when it is null. */
  void attemptFailed(UUID id, int attempts, String error, @Nullable Instant nextAttemptAt);
}
