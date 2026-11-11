package in.dexter.audit.app;

import in.dexter.audit.app.AuditViews.NotificationView;
import in.dexter.audit.app.AuditViews.PrefsView;
import in.dexter.audit.app.NotificationStore.Notification;
import in.dexter.audit.domain.AuditErrors;
import in.dexter.audit.domain.Backoff;
import in.dexter.audit.domain.NotificationRules;
import in.dexter.audit.domain.NotificationRules.Prefs;
import in.dexter.audit.domain.Templates;
import in.dexter.core.error.CoreErrors;
import in.dexter.core.error.DexterException;
import in.dexter.core.error.FieldError;
import in.dexter.core.error.ValidationException;
import in.dexter.core.id.Ids;
import in.dexter.core.tenant.Tenant;
import in.dexter.db.page.Page;
import in.dexter.db.page.PageRequest;
import in.dexter.db.tenant.TenantTx;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Notifications (MASTER_PLAN P18): created from events (in the consumer's transaction), the in-app inbox (keyset),
 * read marks, preferences, and the delivery worker for email and push — claimed across accounts with SKIP LOCKED,
 * sent outside any transaction, retried with exponential backoff and failed after the last attempt.
 */
public final class NotificationService {
  private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
  public static final String PATH = "/v1/notifications";
  public static final String PREFS_PATH = "/v1/notification-prefs";

  /** PATCH /v1/notification-prefs: each field optional. */
  public record PrefsPatch(@Nullable Boolean email, @Nullable Boolean push, @Nullable List<String> muted) {}

  /** The inbox page plus the unread count. */
  public record Inbox(Page<NotificationView> page, long unread) {}

  private final TenantTx tx;
  private final NotificationStore store;
  private final NotificationSender sender;
  private final Backoff backoff;
  private final Clock clock;
  private final Duration lease;

  public NotificationService(TenantTx tx, NotificationStore store, NotificationSender sender, Backoff backoff,
      Clock clock) {
    this.tx = tx;
    this.store = store;
    this.sender = sender;
    this.backoff = backoff;
    this.clock = clock;
    this.lease = Duration.ofSeconds(60);
  }

  /**
   * Creates the notifications of one event, inside the caller's (SYSTEM) transaction: one row per channel the
   * rules pick. Returns the channels written (none when muted, or when this event was already notified).
   */
  public List<String> notify(UUID account, String category, String template, Map<String, Object> payload,
      boolean wantsPush, UUID sourceEventId) {
    Prefs prefs = store.prefs(account).orElse(Prefs.DEFAULTS);
    Instant now = clock.instant();
    List<String> written = new ArrayList<>();
    for (String channel : NotificationRules.channels(category, wantsPush, prefs)) {
      boolean inapp = NotificationRules.INAPP.equals(channel);
      Notification n = new Notification(Ids.newId(), account, channel, category, template, payload,
          inapp ? "sent" : "pending", 0, inapp ? null : now, null, sourceEventId, now, inapp ? now : null, null, 0);
      if (store.insert(n)) {
        written.add(channel);
      }
    }
    return written;
  }

  public Inbox inbox(Tenant tenant, PageRequest page, boolean unreadOnly) {
    return tx.read(tenant, () -> new Inbox(store.inbox(page, unreadOnly).map(NotificationView::of), store.unread()));
  }

  public NotificationView markRead(Tenant tenant, UUID id) {
    return tx.write(tenant, () -> {
      Notification n = store.find(id).filter(x -> NotificationRules.INAPP.equals(x.channel()))
          .orElseThrow(() -> new DexterException(AuditErrors.NOTIFICATION_NOT_FOUND, "No notification with that id"));
      if (n.readAt() == null) {
        store.markRead(id, clock.instant());
      }
      return NotificationView.of(store.find(id).orElseThrow());
    });
  }

  public int markAllRead(Tenant tenant) {
    return tx.write(tenant, () -> store.markAllRead(clock.instant()));
  }

  public PrefsView prefs(Tenant tenant) {
    return tx.read(tenant, () -> view(store.prefs(tenant.accountId()).orElse(Prefs.DEFAULTS)));
  }

  /** PATCH with If-Match (version 0 when the account has never saved preferences). */
  public PrefsView patchPrefs(Tenant tenant, long expectedVersion, PrefsPatch patch) {
    Set<String> muted = null;
    if (patch.muted() != null) {
      muted = new TreeSet<>(patch.muted());
      List<FieldError> errors = new ArrayList<>();
      for (String m : muted) {
        if (!NotificationRules.MUTABLE.contains(m)) {
          errors.add(new FieldError("muted", "NOT_MUTABLE",
              NotificationRules.SECURITY.equals(m) ? "security notifications cannot be muted" : "unknown category"));
        }
      }
      if (!errors.isEmpty()) {
        throw new ValidationException(errors);
      }
    }
    Set<String> newMuted = muted;
    return tx.write(tenant, () -> {
      Prefs current = store.prefs(tenant.accountId()).orElse(Prefs.DEFAULTS);
      if (current.version() != expectedVersion) {
        throw new DexterException(CoreErrors.VERSION_CONFLICT, "The preferences changed; reload them");
      }
      Prefs next = new Prefs(patch.email() == null ? current.email() : patch.email(),
          patch.push() == null ? current.push() : patch.push(), newMuted == null ? current.muted() : newMuted,
          current.version() + 1);
      if (!store.savePrefs(tenant.accountId(), next, current.version(), clock.instant())) {
        throw new DexterException(CoreErrors.VERSION_CONFLICT, "The preferences changed; reload them");
      }
      return view(next);
    });
  }

  static PrefsView view(Prefs p) {
    return new PrefsView(true, p.email(), p.push(), List.copyOf(new TreeSet<>(p.muted())),
        List.copyOf(new TreeSet<>(NotificationRules.CATEGORIES)), List.copyOf(new TreeSet<>(NotificationRules.MUTABLE)),
        p.version());
  }

  /** One worker pass: claim due deliveries, send each, record the outcome. Returns how many were attempted. */
  public int deliverDue(int limit) {
    Instant now = clock.instant();
    List<Notification> claimed = tx.write(AuditTenants.SYSTEM, () -> store.claimDue(now, limit, now.plus(lease)));
    for (Notification n : claimed) {
      int attempt = n.attempts() + 1;
      try {
        sender.send(n, Templates.render(n.template(), n.payload()), attempt);
        tx.writeVoid(AuditTenants.SYSTEM, () -> store.delivered(n.id(), attempt, clock.instant()));
      } catch (RuntimeException e) {
        Instant retryAt = backoff.exhausted(attempt) ? null : clock.instant().plus(backoff.after(attempt));
        String error = String.valueOf(e.getMessage());
        tx.writeVoid(AuditTenants.SYSTEM, () -> store.attemptFailed(n.id(), attempt,
            error.length() > 300 ? error.substring(0, 300) : error, retryAt));
        log.info("NOTIFICATION_ATTEMPT_FAILED id={} channel={} attempt={} retryAt={}", n.id(), n.channel(), attempt,
            retryAt);
      }
    }
    return claimed.size();
  }
}
