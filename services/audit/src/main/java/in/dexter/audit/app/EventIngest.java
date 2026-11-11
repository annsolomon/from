package in.dexter.audit.app;

import in.dexter.audit.app.AuditStore.Entry;
import in.dexter.audit.domain.NotificationRules;
import in.dexter.contracts.events.billing.SubscriptionChangedV1;
import in.dexter.contracts.events.commerce.ApplicationStatusChangedV1;
import in.dexter.contracts.events.gateway.ActivityRecordedV1;
import in.dexter.contracts.events.identity.AccountRegisteredV1;
import in.dexter.contracts.events.identity.AccountVerifiedV1;
import in.dexter.contracts.events.identity.AdminActionV1;
import in.dexter.contracts.events.identity.SecurityAlertV2;
import in.dexter.contracts.events.identity.SessionRevokedV1;
import in.dexter.contracts.events.planning.BudgetThresholdCrossedV1;
import in.dexter.db.tenant.TenantTx;
import in.dexter.events.EventEnvelope;
import in.dexter.events.InboxGuard;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the audit service hears (MASTER_PLAN P18 "Consumers"). One transaction per event, deduplicated by the inbox:
 * identity's account, session, security and admin events, billing's subscription changes and commerce's application
 * decisions become audit entries; security alerts, budget thresholds, subscription changes and application status
 * updates become notifications. Gateway activity has its own path ({@link #onActivity}): the event id is the row's
 * key, so a redelivery inserts nothing without an inbox row per sampled request.
 */
public final class EventIngest {
  public static final String EVENTS_CONSUMER = "events";
  public static final String ACTIVITY_CONSUMER = "activity";
  public static final List<String> EVENT_TOPICS = List.of("dexter.identity.account.v1", "dexter.identity.session.v1",
      "dexter.identity.security.v1", "dexter.identity.admin.v1", SubscriptionChangedV1.TOPIC,
      ApplicationStatusChangedV1.TOPIC, BudgetThresholdCrossedV1.TOPIC);

  private final TenantTx tx;
  private final InboxGuard inbox;
  private final AuditStore audit;
  private final NotificationService notifications;
  private final ActivityService activity;
  private final JsonMapper mapper;

  public EventIngest(TenantTx tx, InboxGuard inbox, AuditStore audit, NotificationService notifications,
      ActivityService activity, JsonMapper mapper) {
    this.tx = tx;
    this.inbox = inbox;
    this.audit = audit;
    this.notifications = notifications;
    this.activity = activity;
    this.mapper = mapper;
  }

  /** The {@code events} consumer. Unknown types (other events on these topics) are skipped. */
  public void onEvent(EventEnvelope e) {
    if (!known(e.type())) {
      return;
    }
    tx.writeVoid(AuditTenants.SYSTEM, () -> {
      if (!inbox.firstDelivery(EVENTS_CONSUMER, e.id())) {
        return;
      }
      apply(e);
    });
  }

  static boolean known(String type) {
    return switch (type) {
      case AccountRegisteredV1.TYPE, AccountVerifiedV1.TYPE, SessionRevokedV1.TYPE, SecurityAlertV2.TYPE,
           AdminActionV1.TYPE, SubscriptionChangedV1.TYPE, ApplicationStatusChangedV1.TYPE,
           BudgetThresholdCrossedV1.TYPE -> true;
      default -> false;
    };
  }

  private void apply(EventEnvelope e) {
    UUID account = e.accountId();
    String who = account == null ? null : account.toString();
    switch (e.type()) {
      case AccountRegisteredV1.TYPE -> {
        AccountRegisteredV1 d = data(e, AccountRegisteredV1.class);
        record(e, "user", d.accountId().toString(), "account", d.accountId().toString(), Map.of());
      }
      case AccountVerifiedV1.TYPE -> {
        AccountVerifiedV1 d = data(e, AccountVerifiedV1.class);
        record(e, "user", d.accountId().toString(), "account", d.accountId().toString(), Map.of());
      }
      case SessionRevokedV1.TYPE -> {
        SessionRevokedV1 d = data(e, SessionRevokedV1.class);
        record(e, "user", who, "session", d.sessionId().toString(), Map.of("reason", d.reason()));
      }
      case SecurityAlertV2.TYPE -> {
        // v1 and v2 share the shape (kind, refId, occurredAt); v2 added kinds
        SecurityAlertV2 d = data(e, SecurityAlertV2.class);
        record(e, "system", null, "account", who, Map.of("kind", d.kind(), "refId", String.valueOf(d.refId())));
        if (account != null) {
          notifications.notify(account, NotificationRules.SECURITY, "security." + d.kind().toLowerCase(Locale.ROOT),
              Map.of("kind", d.kind(), "at", d.occurredAt()), false, e.id());
        }
      }
      case AdminActionV1.TYPE -> {
        AdminActionV1 d = data(e, AdminActionV1.class);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("action", d.action());
        if (d.reason() != null) {
          details.put("reason", d.reason());
        }
        record(e, "admin", d.actorAdminId() == null ? null : d.actorAdminId().toString(),
            targetType(d.targetType()),
            d.targetId() == null ? null : d.targetId().toString(), details);
      }
      case SubscriptionChangedV1.TYPE -> {
        SubscriptionChangedV1 d = data(e, SubscriptionChangedV1.class);
        record(e, "system", null, "subscription", d.subscriptionId().toString(),
            Map.of("account", String.valueOf(who), "plan", d.planCode(), "status", d.status()));
        if (account != null) {
          notifications.notify(account, NotificationRules.BILLING, "billing.subscription",
              Map.of("planCode", d.planCode(), "status", d.status()), false, e.id());
        }
      }
      case ApplicationStatusChangedV1.TYPE -> {
        ApplicationStatusChangedV1 d = data(e, ApplicationStatusChangedV1.class);
        record(e, "system", null, "card_application", d.applicationId().toString(),
            Map.of("account", String.valueOf(who), "status", d.status()));
        if (account != null) {
          Map<String, Object> p = new LinkedHashMap<>();
          p.put("status", d.status());
          p.put("cardProductId", d.cardProductId().toString());
          if (d.reason() != null) {
            p.put("reason", d.reason());
          }
          notifications.notify(account, NotificationRules.COMMERCE, "commerce.application", p, true, e.id());
        }
      }
      case BudgetThresholdCrossedV1.TYPE -> {
        BudgetThresholdCrossedV1 d = data(e, BudgetThresholdCrossedV1.class);
        if (account != null) {
          Map<String, Object> p = new LinkedHashMap<>();
          p.put("budgetId", d.budgetId().toString());
          p.put("name", d.name());
          p.put("period", d.period());
          p.put("threshold", d.threshold());
          p.put("limitPaise", d.limitPaise());
          p.put("spentPaise", d.spentPaise());
          notifications.notify(account, NotificationRules.BUDGET, "budget.threshold", p, false, e.id());
        }
      }
      default -> { }
    }
  }

  private void record(EventEnvelope e, String actorType, @Nullable String actorId, @Nullable String targetType,
      @Nullable String targetId, Map<String, Object> extra) {
    Map<String, Object> details = new LinkedHashMap<>(extra);
    details.put("event", e.id().toString());
    details.put("source", e.source());
    details.put("eventAt", e.time().toString());
    audit.append(new Entry(actorType, actorId, e.type(), targetType, targetId, null, null, details));
  }

  /** An admin action's target type as the log's column takes it (lower snake case, letters first). */
  static @Nullable String targetType(@Nullable String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String t = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
    if (!Character.isLetter(t.charAt(0))) {
      t = "t_" + t;
    }
    return t.length() < 2 ? t + "_" : t.length() > 40 ? t.substring(0, 40) : t;
  }

  private <T> T data(EventEnvelope e, Class<T> type) {
    return mapper.treeToValue(e.data(), type);
  }

  /** The {@code activity} consumer: one sampled gateway request. */
  public void onActivity(EventEnvelope e) {
    if (!ActivityRecordedV1.TYPE.equals(e.type())) {
      return;
    }
    ActivityRecordedV1 d = data(e, ActivityRecordedV1.class);
    activity.record(new ActivityStore.Event(e.id(), Instant.parse(d.occurredAt()), e.accountId(), d.requestId(),
        d.service(), d.routePattern(), d.routeClass(), d.method(), d.status(), d.durationMs()));
  }
}
