package in.dexter.commerce.app;

import in.dexter.commerce.app.AdStore.Campaign;
import in.dexter.commerce.app.AdStore.Creative;
import in.dexter.commerce.app.CommerceViews.CampaignView;
import in.dexter.commerce.domain.CommerceErrors;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * {@code /v1/admin/commerce/campaigns} (operators, admin pool): campaign CRUD — create (with its first creatives),
 * list, read, replace the campaign's settings (If-Match), end it (DELETE: status ended, rows kept for the stats) —
 * plus adding a creative and pausing / re-weighting one (If-Match).
 */
public final class AdAdminService {
  public static final String PATH = "/v1/admin/commerce/campaigns";
  private static final Set<String> STATUSES = Set.of("draft", "active", "paused", "ended");

  public record NewCreative(@Nullable String headline, @Nullable String body, @Nullable String imageUrl,
      @Nullable String clickUrl, @Nullable Integer weight) {}

  public record CampaignInput(@Nullable String name, @Nullable String advertiser, @Nullable String status,
      @Nullable Instant validFrom, @Nullable Instant validTo, @Nullable Integer weight,
      @Nullable Integer frequencyCap, @Nullable Integer frequencyWindowSeconds, @Nullable List<String> placements,
      @Nullable List<NewCreative> creatives) {}

  public record CreativeChange(@Nullable String status, @Nullable Integer weight) {}

  private final TenantTx tx;
  private final AdStore ads;
  private final Clock clock;

  public AdAdminService(TenantTx tx, AdStore ads, Clock clock) {
    this.tx = tx;
    this.ads = ads;
    this.clock = clock;
  }

  public CampaignView create(Tenant operator, CampaignInput in) {
    Instant now = clock.instant();
    return tx.write(operator, () -> {
      List<FieldError> errors = new ArrayList<>();
      Campaign c = campaign(errors, Ids.newId(), in, 0, now, ads.placements());
      List<NewCreative> creatives = in.creatives() == null ? List.of() : in.creatives();
      DealAdminService.require(errors, creatives.size() <= 20, "creatives", "at most 20");
      List<Creative> made = new ArrayList<>();
      for (int i = 0; i < creatives.size(); i++) {
        made.add(creative(errors, "creatives[" + i + "].", c.id(), creatives.get(i)));
      }
      if (!errors.isEmpty()) {
        throw new ValidationException(errors);
      }
      ads.insertCampaign(c, operator.accountId());
      made.forEach(ads::insertCreative);
      return view(c.id());
    });
  }

  public Page<CampaignView> list(Tenant operator, PageRequest page) {
    return tx.read(operator, () -> ads.campaigns(page).map(c -> CampaignView.of(c, ads.creatives(c.id()))));
  }

  public CampaignView get(Tenant operator, UUID id) {
    return tx.read(operator, () -> view(id));
  }

  public CampaignView update(Tenant operator, UUID id, long expectedVersion, CampaignInput in) {
    Instant now = clock.instant();
    return tx.write(operator, () -> {
      Campaign current = ads.campaign(id).orElseThrow(AdAdminService::campaignNotFound);
      if (current.version() != expectedVersion) {
        throw new DexterException(CoreErrors.VERSION_CONFLICT, "The campaign changed; reload it");
      }
      if (in.creatives() != null) {
        throw ValidationException.of("creatives", "NOT_ALLOWED_HERE", "add creatives with POST .../creatives");
      }
      List<FieldError> errors = new ArrayList<>();
      Campaign next = campaign(errors, id, in, current.version(), current.createdAt(), ads.placements());
      if (!errors.isEmpty()) {
        throw new ValidationException(errors);
      }
      if (!ads.updateCampaign(next, current.version(), now)) {
        throw new DexterException(CoreErrors.VERSION_CONFLICT, "The campaign changed; reload it");
      }
      return view(id);
    });
  }

  public void end(Tenant operator, UUID id) {
    Instant now = clock.instant();
    tx.writeVoid(operator, () -> {
      Campaign c = ads.campaign(id).orElseThrow(AdAdminService::campaignNotFound);
      if (!c.status().equals("ended")) {
        Campaign ended = new Campaign(c.id(), c.name(), c.advertiser(), "ended", c.validFrom(), c.validTo(),
            c.weight(), c.frequencyCap(), c.frequencyWindowSeconds(), c.placements(), c.version(), c.createdAt());
        if (!ads.updateCampaign(ended, c.version(), now)) {
          throw new DexterException(CoreErrors.VERSION_CONFLICT, "The campaign changed; retry");
        }
      }
    });
  }

  public CampaignView addCreative(Tenant operator, UUID campaignId, NewCreative in) {
    return tx.write(operator, () -> {
      ads.campaign(campaignId).orElseThrow(AdAdminService::campaignNotFound);
      List<FieldError> errors = new ArrayList<>();
      Creative k = creative(errors, "", campaignId, in);
      if (!errors.isEmpty()) {
        throw new ValidationException(errors);
      }
      ads.insertCreative(k);
      return view(campaignId);
    });
  }

  public CampaignView updateCreative(Tenant operator, UUID campaignId, UUID creativeId, long expectedVersion,
      CreativeChange in) {
    Instant now = clock.instant();
    return tx.write(operator, () -> {
      Creative k = ads.findCreative(campaignId, creativeId)
          .orElseThrow(() -> new DexterException(CommerceErrors.CREATIVE_NOT_FOUND, "No such creative"));
      if (k.version() != expectedVersion) {
        throw new DexterException(CoreErrors.VERSION_CONFLICT, "The creative changed; reload it");
      }
      String status = in.status() == null ? k.status() : in.status();
      int weight = in.weight() == null ? k.weight() : in.weight();
      List<FieldError> errors = new ArrayList<>();
      DealAdminService.require(errors, status.equals("active") || status.equals("paused"), "status",
          "active or paused");
      DealAdminService.require(errors, weight >= 1 && weight <= 1000, "weight", "1..1000");
      if (!errors.isEmpty()) {
        throw new ValidationException(errors);
      }
      if (!ads.updateCreative(creativeId, campaignId, status, weight, k.version(), now)) {
        throw new DexterException(CoreErrors.VERSION_CONFLICT, "The creative changed; reload it");
      }
      return view(campaignId);
    });
  }

  private CampaignView view(UUID id) {
    Campaign c = ads.campaign(id).orElseThrow(AdAdminService::campaignNotFound);
    return CampaignView.of(c, ads.creatives(id));
  }

  private static Campaign campaign(List<FieldError> errors, UUID id, CampaignInput in, int version,
      Instant createdAt, Set<String> known) {
    String name = DealAdminService.text(errors, "name", in.name(), 120);
    String advertiser = DealAdminService.text(errors, "advertiser", in.advertiser(), 120);
    String status = in.status() == null ? "active" : in.status();
    DealAdminService.require(errors, STATUSES.contains(status), "status", "draft, active, paused or ended");
    Instant from = in.validFrom() == null ? createdAt : in.validFrom();
    DealAdminService.require(errors, in.validTo() == null || in.validTo().isAfter(from), "validTo",
        "after validFrom");
    int weight = in.weight() == null ? 1 : in.weight();
    DealAdminService.require(errors, weight >= 1 && weight <= 1000, "weight", "1..1000");
    int cap = in.frequencyCap() == null ? 0 : in.frequencyCap();
    DealAdminService.require(errors, cap >= 1 && cap <= 1000, "frequencyCap", "1..1000");
    int window = in.frequencyWindowSeconds() == null ? 86_400 : in.frequencyWindowSeconds();
    DealAdminService.require(errors, window >= 60 && window <= 2_592_000, "frequencyWindowSeconds",
        "60..2592000");
    List<String> placements = in.placements() == null ? List.of() : List.copyOf(new LinkedHashSet<>(in.placements()));
    DealAdminService.require(errors, !placements.isEmpty() && placements.size() <= 10, "placements", "1..10");
    for (String p : placements) {
      if (!known.contains(p)) {
        errors.add(new FieldError("placements", "PLACEMENT_UNKNOWN", "no placement " + p));
      }
    }
    return new Campaign(id, name, advertiser, status, from, in.validTo(), weight, cap, window, placements, version,
        createdAt);
  }

  private static Creative creative(List<FieldError> errors, String prefix, UUID campaignId, NewCreative in) {
    String headline = DealAdminService.text(errors, prefix + "headline", in.headline(), 90);
    String body = in.body() == null ? "" : in.body().strip();
    DealAdminService.require(errors, body.length() <= 300, prefix + "body", "at most 300 characters");
    DealAdminService.require(errors, in.imageUrl() == null || (in.imageUrl().length() <= 2000
        && DealAdminService.HTTPS.matcher(in.imageUrl()).matches()), prefix + "imageUrl", "an https URL");
    DealAdminService.require(errors, in.clickUrl() != null && in.clickUrl().length() <= 2000
        && DealAdminService.HTTPS.matcher(in.clickUrl()).matches(), prefix + "clickUrl", "an https URL");
    int weight = in.weight() == null ? 1 : in.weight();
    DealAdminService.require(errors, weight >= 1 && weight <= 1000, prefix + "weight", "1..1000");
    return new Creative(Ids.newId(), campaignId, headline, body, in.imageUrl(),
        in.clickUrl() == null ? "" : in.clickUrl(), weight, "active", 0);
  }

  private static DexterException campaignNotFound() {
    return new DexterException(CommerceErrors.CAMPAIGN_NOT_FOUND, "No campaign with that id");
  }
}
