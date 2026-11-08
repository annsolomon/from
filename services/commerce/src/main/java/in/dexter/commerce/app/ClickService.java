package in.dexter.commerce.app;

import in.dexter.commerce.app.AdStore.Live;
import in.dexter.commerce.app.DealStore.Deal;
import in.dexter.commerce.domain.ClickToken;
import in.dexter.commerce.domain.CommerceErrors;
import in.dexter.commerce.domain.Hashing;
import in.dexter.core.error.DexterException;
import in.dexter.core.id.Ids;
import in.dexter.core.time.Clocks;
import in.dexter.db.tenant.TenantTx;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code GET /r/{token}} (MASTER_PLAN P17): verify the signed link, log the click, count it, redirect.
 *
 * <p>A deal click writes one raw row (deal_clicks, partitioned daily) in the TRACKER transaction, then increments
 * the Redis counters after commit and redirects to the deal's landing URL with {@code subid=<click id>} — the id the
 * affiliate network echoes back in its postback. Attribution is the token's account (who was shown the link), never
 * the redirect request's: a link forwarded to someone else stays the original viewer's. An ad click counts and
 * redirects to the creative's URL. A Redis failure loses that count (logged) but never the redirect or the raw row.
 */
public final class ClickService {
  private static final Logger log = LoggerFactory.getLogger(ClickService.class);

  private final TenantTx tx;
  private final DealStore deals;
  private final AdStore ads;
  private final TrackingStore tracking;
  private final CounterBuffer counters;
  private final ClickToken tokens;
  private final String salt;
  private final Clock clock;

  public ClickService(TenantTx tx, DealStore deals, AdStore ads, TrackingStore tracking, CounterBuffer counters,
      ClickToken tokens, String salt, Clock clock) {
    this.tx = tx;
    this.deals = deals;
    this.ads = ads;
    this.tracking = tracking;
    this.counters = counters;
    this.tokens = tokens;
    this.salt = salt;
    this.clock = clock;
  }

  public URI redirect(String token, Viewer request) {
    Instant now = clock.instant();
    ClickToken.Verified v = tokens.verify(token, now);
    switch (v.outcome()) {
      case INVALID -> throw new DexterException(CommerceErrors.CLICK_TOKEN_INVALID, "This link is not valid");
      case EXPIRED -> throw new DexterException(CommerceErrors.CLICK_TOKEN_EXPIRED, "This link has expired");
      case OK -> { }
    }
    ClickToken.Claims c = v.claims();
    return switch (c.kind()) {
      case DEAL -> deal(token, c, request, now);
      case AD -> ad(c, now);
    };
  }

  private URI deal(String token, ClickToken.Claims c, Viewer request, Instant now) {
    UUID clickId = Ids.newId();
    Instant at = Ids.timestampOf(clickId);
    Deal d = tx.write(CommerceTenants.TRACKER, () -> {
      Deal deal = deals.findDeal(c.targetId()).filter(x -> x.liveAt(now))
          .orElseThrow(() -> new DexterException(CommerceErrors.DEAL_UNAVAILABLE, "This deal has ended"));
      tracking.insertClick(new TrackingStore.Click(clickId, at, c.accountId(), deal.id(), ClickToken.hash(token),
          request.ipHash(), request.uaHash()));
      return deal;
    });
    try {
      counters.dealClick(d.id(), day(at),
          Hashing.visitor(salt, c.accountId(), request.ipHash(), request.uaHash()));
    } catch (RuntimeException e) {
      log.warn("COMMERCE_COUNTER_LOST metric=deal_click deal={} cause={}", d.id(), e.toString());
    }
    String sep = d.landingUrl().contains("?") ? "&" : "?";
    return URI.create(d.landingUrl() + sep + "subid=" + URLEncoder.encode(clickId.toString(), StandardCharsets.UTF_8));
  }

  private URI ad(ClickToken.Claims c, Instant now) {
    Live live = tx.read(CommerceTenants.TRACKER, () -> ads.creative(c.targetId()))
        .filter(l -> "active".equals(l.campaign().status()) && !now.isBefore(l.campaign().validFrom())
            && (l.campaign().validTo() == null || now.isBefore(l.campaign().validTo())))
        .orElseThrow(() -> new DexterException(CommerceErrors.AD_NOT_FOUND, "This ad has ended"));
    try {
      counters.adClick(live.creative().id(), day(now));
    } catch (RuntimeException e) {
      log.warn("COMMERCE_COUNTER_LOST metric=ad_click creative={} cause={}", live.creative().id(), e.toString());
    }
    return URI.create(live.creative().clickUrl());
  }

  static LocalDate day(Instant at) {
    return Clocks.dayOf(at, Clocks.DEFAULT_ZONE);
  }
}
