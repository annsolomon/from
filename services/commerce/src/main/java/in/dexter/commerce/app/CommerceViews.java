package in.dexter.commerce.app;

import in.dexter.commerce.app.AdStore.Campaign;
import in.dexter.commerce.app.AdStore.Creative;
import in.dexter.commerce.app.ApplicationStore.Application;
import in.dexter.commerce.app.ApplicationStore.HistoryEntry;
import in.dexter.commerce.app.DealStore.Deal;
import in.dexter.commerce.domain.SavingComponent;
import in.dexter.core.error.FieldError;
import in.dexter.core.error.ValidationException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** The JSON shapes commerce answers with (and the component shape it accepts). */
public final class CommerceViews {
  private CommerceViews() {}

  /** A saving component on the wire and in deals.components: percent (valueBps, capPaise) or flat (valuePaise). */
  public record Component(String kind, @Nullable Integer valueBps, @Nullable Long capPaise,
      @Nullable Long valuePaise, @Nullable Long minSpendPaise) {
    public static Component of(SavingComponent c) {
      return switch (c) {
        case SavingComponent.Percent p -> new Component("percent", p.valueBps(), p.capPaise(), null,
            p.minSpendPaise());
        case SavingComponent.Flat f -> new Component("flat", null, null, f.valuePaise(), f.minSpendPaise());
      };
    }

    public SavingComponent toDomain() {
      long min = minSpendPaise == null ? 0 : minSpendPaise;
      return switch (kind == null ? "" : kind.toLowerCase(Locale.ROOT)) {
        case "percent" -> new SavingComponent.Percent(valueBps == null ? 0 : valueBps, capPaise == null ? 0 : capPaise,
            min);
        case "flat" -> new SavingComponent.Flat(valuePaise == null ? 0 : valuePaise, min);
        default -> throw new IllegalArgumentException("kind must be percent or flat");
      };
    }

    /** Validated domain components (field errors name the index). */
    public static List<SavingComponent> toDomain(@Nullable List<Component> in) {
      if (in == null || in.isEmpty() || in.size() > 5) {
        throw ValidationException.of("components", "COMPONENTS_INVALID", "1..5 components");
      }
      List<SavingComponent> out = new ArrayList<>(in.size());
      List<FieldError> errors = new ArrayList<>();
      for (int i = 0; i < in.size(); i++) {
        try {
          out.add(in.get(i).toDomain());
        } catch (IllegalArgumentException | NullPointerException e) {
          errors.add(new FieldError("components[" + i + "]", "COMPONENT_INVALID", String.valueOf(e.getMessage())));
        }
      }
      if (!errors.isEmpty()) {
        throw new ValidationException(errors);
      }
      return out;
    }
  }

  public record EligibleCard(UUID cardProductId, @Nullable String name) {}

  /** A deal as the feed and the public detail show it (no landing URL: clicks go through {@code clickPath}). */
  public record DealView(UUID id, UUID merchantId, String merchantName, String title, String description,
      String categoryPath, List<Component> components, List<EligibleCard> eligibleCards, long referenceSpendPaise,
      long savingsPaise, String cardMatch, boolean sponsored, int scoreBps, Instant validFrom,
      @Nullable Instant validTo, String clickPath) {}

  public record SavingLine(Component component, long savingPaise) {}

  public record DealDetailView(DealView deal, long spendPaise, List<SavingLine> lines, long totalSavingsPaise) {}

  /** What operators see. */
  public record AdminDealView(UUID id, UUID merchantId, String merchantName, String title, String description,
      String categoryPath, List<Component> components, List<UUID> eligibleCardProducts, long referenceSpendPaise,
      Instant validFrom, @Nullable Instant validTo, String status, int priority, boolean sponsored,
      int sponsorWeight, String network, String landingUrl, int commissionBps, int version, Instant createdAt) {
    public static AdminDealView of(Deal d) {
      return new AdminDealView(d.id(), d.merchantId(), d.merchantName(), d.title(), d.description(),
          d.categoryPath(), d.components().stream().map(Component::of).toList(), d.eligibleCardProducts(),
          d.referenceSpendPaise(), d.validFrom(), d.validTo(), d.status(), d.priority(), d.sponsored(),
          d.sponsorWeight(), d.network(), d.landingUrl(), d.commissionBps(), d.version(), d.createdAt());
    }
  }

  public record ApplicationView(UUID id, UUID cardProductId, String status, List<HistoryEntry> history,
      @Nullable String reason, int version, Instant createdAt, Instant updatedAt, @Nullable Instant decidedAt) {
    public static ApplicationView of(Application a) {
      return new ApplicationView(a.id(), a.cardProductId(), a.status().db(), a.history(), a.reason(), a.version(),
          a.createdAt(), a.updatedAt(), a.decidedAt());
    }
  }

  /** Operators also see whose application it is. */
  public record AdminApplicationView(UUID id, UUID accountId, UUID cardProductId, String status,
      List<HistoryEntry> history, @Nullable String reason, int version, Instant createdAt, Instant updatedAt,
      @Nullable Instant decidedAt, @Nullable UUID decidedBy) {
    public static AdminApplicationView of(Application a) {
      return new AdminApplicationView(a.id(), a.accountId(), a.cardProductId(), a.status().db(), a.history(),
          a.reason(), a.version(), a.createdAt(), a.updatedAt(), a.decidedAt(), a.decidedBy());
    }
  }

  public record AdView(UUID creativeId, UUID campaignId, String headline, String body, @Nullable String imageUrl,
      String clickPath) {}

  /** {@code ad} is null when nothing is served; {@code reason} then says why (AD_FREE, NO_FILL, FREQUENCY_CAPPED). */
  public record AdServeView(String placement, @Nullable AdView ad, @Nullable String reason) {}

  public record CreativeView(UUID id, String headline, String body, @Nullable String imageUrl, String clickUrl,
      int weight, String status, int version) {
    public static CreativeView of(Creative k) {
      return new CreativeView(k.id(), k.headline(), k.body(), k.imageUrl(), k.clickUrl(), k.weight(), k.status(),
          k.version());
    }
  }

  public record CampaignView(UUID id, String name, String advertiser, String status, Instant validFrom,
      @Nullable Instant validTo, int weight, int frequencyCap, int frequencyWindowSeconds, List<String> placements,
      int version, Instant createdAt, List<CreativeView> creatives) {
    public static CampaignView of(Campaign c, List<Creative> creatives) {
      return new CampaignView(c.id(), c.name(), c.advertiser(), c.status(), c.validFrom(), c.validTo(), c.weight(),
          c.frequencyCap(), c.frequencyWindowSeconds(), c.placements(), c.version(), c.createdAt(),
          creatives.stream().map(CreativeView::of).toList());
    }
  }

  public record StatsView(String from, String to, List<TrackingStore.DealStat> deals,
      List<AdStore.CampaignStat> campaigns) {}

  public record PostbackResult(String outcome, UUID conversionId, String status) {}
}
