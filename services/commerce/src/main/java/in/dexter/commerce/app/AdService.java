package in.dexter.commerce.app;

import in.dexter.commerce.app.AdStore.Live;
import in.dexter.commerce.app.CommerceViews.AdServeView;
import in.dexter.commerce.app.CommerceViews.AdView;
import in.dexter.commerce.domain.AdRotation;
import in.dexter.commerce.domain.CommerceErrors;
import in.dexter.commerce.domain.Hashing;
import in.dexter.core.error.DexterException;
import in.dexter.core.id.Ids;
import in.dexter.db.tenant.TenantTx;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * {@code GET /v1/ads/serve?placement=} (MASTER_PLAN P17; public, token optional). Ad-free viewers (billing's
 * {@code adFree} entitlement, projected from {@code billing.entitlements.changed}) get no ad. Otherwise a weighted
 * draw over the placement's live (campaign, creative) pairs; the chosen campaign must be under its frequency cap
 * for this visitor — checked and counted atomically in Redis, so concurrent serves never exceed it — or its pairs
 * leave the draw and it is repeated. A served ad writes one raw impression and carries a signed click path.
 */
public final class AdService {
  public static final String PATH = "/v1/ads";

  private final TenantTx tx;
  private final AdStore ads;
  private final ProjectionStore projections;
  private final TrackingStore tracking;
  private final CounterBuffer counters;
  private final ClickLinks links;
  private final String salt;
  private final RandomGenerator random;
  private final Clock clock;

  public AdService(TenantTx tx, AdStore ads, ProjectionStore projections, TrackingStore tracking,
      CounterBuffer counters, ClickLinks links, String salt, RandomGenerator random, Clock clock) {
    this.tx = tx;
    this.ads = ads;
    this.projections = projections;
    this.tracking = tracking;
    this.counters = counters;
    this.links = links;
    this.salt = salt;
    this.random = random;
    this.clock = clock;
  }

  public AdServeView serve(Viewer viewer, String placement) {
    Instant now = clock.instant();
    record Read(boolean adFree, List<Live> live) {}
    Read read = tx.read(viewer.tenant(), () -> {
      if (!ads.placements().contains(placement)) {
        throw new DexterException(CommerceErrors.PLACEMENT_UNKNOWN, "No placement " + placement);
      }
      if (viewer.signedIn() && projections.adFree()) {
        return new Read(true, List.of());
      }
      return new Read(false, ads.live(placement, now));
    });
    if (read.adFree()) {
      return new AdServeView(placement, null, "AD_FREE");
    }
    if (read.live().isEmpty()) {
      return new AdServeView(placement, null, "NO_FILL");
    }
    byte[] visitor = Hashing.visitor(salt, viewer.accountId(), viewer.ipHash(), viewer.uaHash());
    Map<UUID, Live> byCreative = new HashMap<>();
    List<AdRotation.Option> options = new ArrayList<>();
    for (Live l : read.live()) {
      byCreative.put(l.creative().id(), l);
      options.add(new AdRotation.Option(l.campaign().id(), l.creative().id(),
          (long) l.campaign().weight() * l.creative().weight()));
    }
    while (!options.isEmpty()) {
      AdRotation.Option pick = AdRotation.pick(options, random.nextLong(AdRotation.totalWeight(options)));
      Live l = byCreative.get(pick.creativeId());
      if (counters.impression(l.campaign().id(), l.creative().id(), ClickService.day(now), visitor,
          l.campaign().frequencyCap(), Duration.ofSeconds(l.campaign().frequencyWindowSeconds()))) {
        UUID id = Ids.newId();
        tx.writeVoid(CommerceTenants.TRACKER, () -> tracking.insertImpression(id, Ids.timestampOf(id),
            l.creative().id(), l.campaign().id(), placement, viewer.accountId(), visitor));
        return new AdServeView(placement, new AdView(l.creative().id(), l.campaign().id(), l.creative().headline(),
            l.creative().body(), l.creative().imageUrl(), links.ad(l.creative().id(), viewer.accountId())), null);
      }
      UUID capped = pick.campaignId();
      options.removeIf(o -> o.campaignId().equals(capped));
    }
    return new AdServeView(placement, null, "FREQUENCY_CAPPED");
  }
}
