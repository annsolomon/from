package in.dexter.commerce.app;

import in.dexter.commerce.app.ApplicationStore.Application;
import in.dexter.commerce.app.ApplicationStore.HistoryEntry;
import in.dexter.commerce.app.CommerceViews.ApplicationView;
import in.dexter.commerce.domain.ApplicationStatus;
import in.dexter.commerce.domain.CommerceErrors;
import in.dexter.contracts.events.commerce.ApplicationStatusChangedV1;
import in.dexter.core.error.CoreErrors;
import in.dexter.core.error.DexterException;
import in.dexter.core.error.FieldError;
import in.dexter.core.error.ValidationException;
import in.dexter.core.id.Ids;
import in.dexter.core.tenant.Tenant;
import in.dexter.db.page.Page;
import in.dexter.db.page.PageRequest;
import in.dexter.db.tenant.TenantTx;
import in.dexter.events.OutboxWriter;
import in.dexter.idempotency.ApiResponse;
import in.dexter.idempotency.IdempotencyEngine;
import in.dexter.idempotency.IdempotentRequest;
import in.dexter.idempotency.IdempotentResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code /v1/card-applications} (MASTER_PLAN P17): apply (Idempotency-Key required), list, read, withdraw. The card
 * must be a published product in the catalog projection; one open application per card (the partial unique index
 * answers a race the pre-check missed). Every status change publishes {@code commerce.application.status-changed}
 * in the same transaction (outbox).
 */
public final class ApplicationService {
  public static final String PATH = "/v1/card-applications";

  public record NewApplication(@Nullable UUID cardProductId) {}

  private final TenantTx tx;
  private final ApplicationStore apps;
  private final DealStore catalog;
  private final IdempotencyEngine idempotency;
  private final OutboxWriter outbox;
  private final JsonMapper mapper;
  private final Clock clock;
  private final AtomicBoolean failNext = new AtomicBoolean();

  public ApplicationService(TenantTx tx, ApplicationStore apps, DealStore catalog, IdempotencyEngine idempotency,
      OutboxWriter outbox, JsonMapper mapper, Clock clock) {
    this.tx = tx;
    this.apps = apps;
    this.catalog = catalog;
    this.idempotency = idempotency;
    this.outbox = outbox;
    this.mapper = mapper;
    this.clock = clock;
  }

  public IdempotentResponse apply(Tenant tenant, UUID key, NewApplication in) {
    if (in.cardProductId() == null) {
      throw ValidationException.of("cardProductId", "REQUIRED", "the card product to apply for");
    }
    UUID card = in.cardProductId();
    IdempotentRequest fingerprint = IdempotentRequest.of("POST", PATH, in, mapper);
    return tx.write(tenant, () -> idempotency.execute(tenant, key, fingerprint, () -> {
      if (!catalog.publishedCardNames(List.of(card)).containsKey(card)) {
        throw new ValidationException(List.of(
            new FieldError("cardProductId", "UNKNOWN_CARD_PRODUCT", "No such card in the catalog")));
      }
      Instant now = clock.instant();
      Application a = new Application(Ids.newId(), tenant.accountId(), card, ApplicationStatus.SUBMITTED,
          List.of(new HistoryEntry(ApplicationStatus.SUBMITTED.db(), now, tenant.accountId(), null)), null, 1, now,
          now, null, null);
      if (!apps.insert(a)) {
        throw new DexterException(CommerceErrors.APPLICATION_EXISTS,
            "You already have an open application for this card");
      }
      publish(outbox, a, null);
      if (failNext.getAndSet(false)) {
        throw new IllegalStateException("injected failure after the business write");
      }
      return new ApiResponse(201, Map.of("Location", PATH + "/" + a.id(), "ETag", "\"" + a.version() + "\""),
          ApplicationView.of(a));
    }));
  }

  /** Test hook (the idempotency contract): the next apply fails after its writes, inside the transaction. */
  public void failNextApply() {
    failNext.set(true);
  }

  public Page<ApplicationView> list(Tenant tenant, PageRequest page) {
    return tx.read(tenant, () -> apps.list(page).map(ApplicationView::of));
  }

  public ApplicationView get(Tenant tenant, UUID id) {
    return tx.read(tenant, () -> ApplicationView.of(find(apps, id)));
  }

  public ApplicationView withdraw(Tenant tenant, UUID id) {
    return tx.write(tenant, () -> {
      Application a = apps.lock(id).orElseThrow(ApplicationService::notFound);
      if (!a.status().isOpen()) {
        throw new DexterException(CommerceErrors.APPLICATION_NOT_OPEN, "This application is " + a.status().db());
      }
      Instant now = clock.instant();
      if (!apps.transition(id, a.version(), ApplicationStatus.WITHDRAWN,
          new HistoryEntry(ApplicationStatus.WITHDRAWN.db(), now, tenant.accountId(), null), null, null, null)) {
        throw new DexterException(CoreErrors.VERSION_CONFLICT, "The application changed; reload it");
      }
      Application after = find(apps, id);
      publish(outbox, after, a.status());
      return ApplicationView.of(after);
    });
  }

  static Application find(ApplicationStore apps, UUID id) {
    return apps.find(id).orElseThrow(ApplicationService::notFound);
  }

  static DexterException notFound() {
    return new DexterException(CommerceErrors.APPLICATION_NOT_FOUND, "No card application with that id");
  }

  static void publish(OutboxWriter outbox, Application a, @Nullable ApplicationStatus previous) {
    outbox.append(CommerceEvents.APPLICATION_CHANGED, a.accountId(), a.id(), a.version(),
        new ApplicationStatusChangedV1(a.id(), a.cardProductId(), a.status().db(),
            previous == null ? null : previous.db(), a.reason(), a.version(), a.updatedAt().toString()));
  }
}
