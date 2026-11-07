package in.dexter.billing.app;

import in.dexter.billing.app.BillingStore.EntitlementStateRow;
import in.dexter.billing.app.BillingStore.OverrideRow;
import in.dexter.billing.app.BillingStore.PlanRow;
import in.dexter.billing.app.BillingStore.SubscriptionRow;
import in.dexter.billing.app.BillingViews.EntitlementsView;
import in.dexter.billing.app.BillingViews.OverrideView;
import in.dexter.billing.domain.Entitlements;
import in.dexter.cache.CacheInvalidator;
import in.dexter.cache.CacheScope;
import in.dexter.cache.TenantCache;
import in.dexter.contracts.events.billing.EntitlementsChangedV1;
import in.dexter.core.tenant.Tenant;
import in.dexter.db.tenant.TenantTx;
import in.dexter.events.OutboxWriter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.ObjectMapper;

/**
 * Entitlements (MASTER_PLAN P16): {@code GET /v1/entitlements/me}, cached 60 s per account in Redis (dexter-cache,
 * ADR-0004), and {@link #refresh} - called inside every write transaction that can change them - which compares the
 * effective tier and map with what billing last announced and, when they differ, bumps the per-account version,
 * publishes {@code billing.entitlements.changed} and invalidates the cache entry after the commit.
 *
 * <p>The cache is a read-through convenience, not a source of truth: without Redis (or with Redis down) the read
 * goes to Postgres. An entry is dropped after every change that commits (Path A); the 60 s TTL bounds the one case
 * that path cannot cover, an override lapsing on its own.
 */
public final class EntitlementService {
  public static final String PATH = "/v1/entitlements/me";
  public static final CacheScope SCOPE = CacheScope.tenant("entitlements", Duration.ofSeconds(60));

  private final TenantTx tx;
  private final BillingStore store;
  private final OutboxWriter outbox;
  private final ObjectMapper mapper;
  private final Clock clock;
  private final @Nullable TenantCache cache;
  private final @Nullable CacheInvalidator invalidator;

  public EntitlementService(TenantTx tx, BillingStore store, OutboxWriter outbox, ObjectMapper mapper, Clock clock,
      @Nullable TenantCache cache, @Nullable CacheInvalidator invalidator) {
    this.tx = tx;
    this.store = store;
    this.outbox = outbox;
    this.mapper = mapper;
    this.clock = clock;
    this.cache = cache;
    this.invalidator = invalidator;
  }

  /** The account's entitlements now (cached; the loader opens its own read transaction). */
  public EntitlementsView me(Tenant tenant) {
    if (cache == null) {
      return load(tenant);
    }
    return cache.get(tenant, SCOPE, Map.of(), EntitlementsView.class, () -> load(tenant));
  }

  private EntitlementsView load(Tenant tenant) {
    return tx.read(tenant, () -> {
      Current c = current(now());
      long version = store.entitlementState().map(EntitlementStateRow::version).orElse(0L);
      return new EntitlementsView(c.effective().tier(), c.planCode(), c.subscriptionStatus(),
          c.effective().entitlements(), c.overrides(), version);
    });
  }

  /**
   * Inside a write transaction of {@code tenant}: publishes {@code billing.entitlements.changed} if the tier or the
   * effective map differs from what was last announced (an account never announced is implicitly FREE with FREE's
   * map). Returns whether anything was published.
   */
  public boolean refresh(Tenant tenant, Instant at) {
    Current c = current(at);
    Optional<EntitlementStateRow> state = store.entitlementState();
    String lastTier;
    Map<String, Object> lastMap;
    if (state.isPresent()) {
      lastTier = state.get().tier();
      lastMap = new TreeMap<>(parseMap(state.get().entitlementsJson()));
    } else {
      lastTier = Entitlements.FREE;
      lastMap = new TreeMap<>(freeMap());
    }
    Map<String, Object> now = new TreeMap<>(c.effective().entitlements());
    if (lastTier.equals(c.effective().tier()) && lastMap.equals(now)) {
      return false;
    }
    long version = state.map(EntitlementStateRow::version).orElse(0L) + 1;
    store.saveEntitlementState(new EntitlementStateRow(tenant.accountId(), c.effective().tier(),
        mapper.writeValueAsString(now), version, at));
    outbox.append(BillingEvents.ENTITLEMENTS_CHANGED, tenant.accountId(), tenant.accountId(), version,
        new EntitlementsChangedV1(tenant.accountId(), c.effective().tier(), new LinkedHashMap<>(now), version,
            at.toString()));
    if (invalidator != null) {
      invalidator.afterCommit(tenant.accountId(), SCOPE);
    }
    return true;
  }

  private record Current(Entitlements.Effective effective, String planCode, String subscriptionStatus,
      List<OverrideView> overrides) {}

  private Current current(Instant at) {
    Optional<SubscriptionRow> live = store.live();
    String status = live.map(SubscriptionRow::status).orElse(null);
    String code = live.map(SubscriptionRow::planCode).orElse(null);
    String tier = Entitlements.tier(status, code);
    PlanRow plan = store.planByCode(tier).orElseThrow(() -> new IllegalStateException("no plan " + tier));
    List<Entitlements.Override> overrides = new ArrayList<>();
    for (OverrideRow o : store.overrides()) {
      overrides.add(new Entitlements.Override(o.key(), mapper.readValue(o.valueJson(), Object.class), o.expiresAt()));
    }
    Entitlements.Effective e = Entitlements.effective(tier, parseMap(plan.entitlementsJson()), overrides, at);
    List<OverrideView> views = Entitlements.active(overrides, at).stream()
        .map(o -> new OverrideView(o.key(), o.value(), o.expiresAt())).toList();
    return new Current(e, Entitlements.FREE.equals(tier) ? Entitlements.FREE : code, status, views);
  }

  private Map<String, Object> freeMap() {
    return parseMap(store.planByCode(Entitlements.FREE).orElseThrow().entitlementsJson());
  }

  @SuppressWarnings("unchecked")
  Map<String, Object> parseMap(String json) {
    return mapper.readValue(json, Map.class);
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }
}
