package in.dexter.catalog.domain;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The editable content of a card product (a draft, MASTER_PLAN P09): everything a published version freezes. Field
 * names match the published snapshot (and the seed's snapshots), so a snapshot reads back as a document.
 */
public record CardDocument(String slug, String name, String issuerCode, String network, String tier,
    Long joiningFeePaise, Long annualFeePaise, Long feeWaiverSpendPaise, Integer aprBps, Integer forexMarkupBps,
    Integer fuelSurchargeWaiverBps, Map<String, Object> features, List<Rule> rules, Milestone milestone) {

  /** One reward rule. Scope = (categoryPath, merchantId); both null = the base rate. */
  public record Rule(String categoryPath, UUID merchantId, String merchantSlug, String rewardType, Integer rateBps,
      Long pointValueMilliPaise, Long capPaise, String capPeriod, Long minTxnPaise, List<String> excludedPaths,
      Integer priority) {}

  public record Milestone(Long spendThresholdPaise, String period, String benefitType, Long benefitValuePaise) {}
}
