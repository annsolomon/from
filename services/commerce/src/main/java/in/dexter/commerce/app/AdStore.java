package in.dexter.commerce.app;

import in.dexter.db.page.Page;
import in.dexter.db.page.PageRequest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Campaigns, creatives, placements and the campaign stats. Infra implements it. */
public interface AdStore {
  record Campaign(UUID id, String name, String advertiser, String status, Instant validFrom,
      @Nullable Instant validTo, int weight, int frequencyCap, int frequencyWindowSeconds, List<String> placements,
      int version, Instant createdAt) {
    public Campaign {
      placements = List.copyOf(placements);
    }
  }

  record Creative(UUID id, UUID campaignId, String headline, String body, @Nullable String imageUrl,
      String clickUrl, int weight, String status, int version) {}

  /** A campaign and one of its active creatives, both live for a placement now. */
  record Live(Campaign campaign, Creative creative) {}

  record CampaignStat(UUID campaignId, String name, long impressions, long clicks) {}

  Set<String> placements();

  List<Live> live(String placement, Instant now);

  /** A creative and its campaign, for an ad click (active ones only on the app pool). */
  Optional<Live> creative(UUID creativeId);

  void insertCampaign(Campaign c, @Nullable UUID createdBy);

  boolean updateCampaign(Campaign c, int expectedVersion, Instant at);

  Optional<Campaign> campaign(UUID id);

  Page<Campaign> campaigns(PageRequest page);

  void insertCreative(Creative k);

  boolean updateCreative(UUID id, UUID campaignId, String status, int weight, int expectedVersion, Instant at);

  Optional<Creative> findCreative(UUID campaignId, UUID creativeId);

  List<Creative> creatives(UUID campaignId);

  List<CampaignStat> campaignStats(LocalDate from, LocalDate to);
}
