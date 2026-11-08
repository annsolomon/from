package in.dexter.commerce.app;

import in.dexter.commerce.app.ApplicationStore.Application;
import in.dexter.commerce.app.ApplicationStore.HistoryEntry;
import in.dexter.commerce.app.CommerceViews.AdminApplicationView;
import in.dexter.commerce.domain.ApplicationStatus;
import in.dexter.commerce.domain.CommerceErrors;
import in.dexter.core.error.CoreErrors;
import in.dexter.core.error.DexterException;
import in.dexter.core.error.ValidationException;
import in.dexter.core.tenant.Tenant;
import in.dexter.db.page.Page;
import in.dexter.db.page.PageRequest;
import in.dexter.db.tenant.TenantTx;
import in.dexter.events.OutboxWriter;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * {@code /v1/admin/commerce/card-applications} (operators, admin pool): the queue of open applications (oldest
 * first) and the decision — in_review, approved or rejected, If-Match on the version, the reason kept in the history
 * and the event. RLS on the admin pool admits operators to every account's applications and nobody else.
 */
public final class ApplicationAdminService {
  public static final String PATH = "/v1/admin/commerce/card-applications";

  public record Decision(@Nullable String decision, @Nullable String reason) {}

  private final TenantTx tx;
  private final ApplicationStore apps;
  private final OutboxWriter outbox;
  private final Clock clock;

  public ApplicationAdminService(TenantTx tx, ApplicationStore apps, OutboxWriter outbox, Clock clock) {
    this.tx = tx;
    this.apps = apps;
    this.outbox = outbox;
    this.clock = clock;
  }

  public Page<AdminApplicationView> queue(Tenant operator, PageRequest page) {
    return tx.read(operator, () -> apps.queue(page).map(AdminApplicationView::of));
  }

  public AdminApplicationView get(Tenant operator, UUID id) {
    return tx.read(operator, () -> AdminApplicationView.of(ApplicationService.find(apps, id)));
  }

  public AdminApplicationView decide(Tenant operator, UUID id, long expectedVersion, Decision in) {
    ApplicationStatus next;
    try {
      next = in.decision() == null ? null : ApplicationStatus.ofDb(in.decision());
    } catch (IllegalArgumentException e) {
      next = null;
    }
    if (next == null || !(next == ApplicationStatus.IN_REVIEW || next.isDecision())) {
      throw ValidationException.of("decision", "DECISION_INVALID", "in_review, approved or rejected");
    }
    String reason = in.reason() == null || in.reason().isBlank() ? null : in.reason().strip();
    if (reason != null && reason.length() > 500) {
      throw ValidationException.of("reason", "REASON_TOO_LONG", "at most 500 characters");
    }
    if (next == ApplicationStatus.REJECTED && reason == null) {
      throw ValidationException.of("reason", "REASON_REQUIRED", "a rejection needs a reason");
    }
    ApplicationStatus decided = next;
    return tx.write(operator, () -> {
      Application a = apps.lock(id).orElseThrow(ApplicationService::notFound);
      if (a.version() != expectedVersion) {
        throw new DexterException(CoreErrors.VERSION_CONFLICT, "The application changed; reload it");
      }
      if (!a.status().operatorMayMoveTo(decided)) {
        throw new DexterException(CommerceErrors.APPLICATION_TRANSITION_INVALID,
            a.status().db() + " cannot move to " + decided.db());
      }
      Instant now = clock.instant();
      boolean moved = apps.transition(id, a.version(), decided,
          new HistoryEntry(decided.db(), now, operator.accountId(), reason), reason,
          decided.isDecision() ? now : null, decided.isDecision() ? operator.accountId() : null);
      if (!moved) {
        throw new DexterException(CoreErrors.VERSION_CONFLICT, "The application changed; reload it");
      }
      Application after = ApplicationService.find(apps, id);
      ApplicationService.publish(outbox, after, a.status());
      return AdminApplicationView.of(after);
    });
  }
}
