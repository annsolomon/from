package in.dexter.catalog.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The published snapshot of a card version (MASTER_PLAN P09): what {@code card_product_versions.snapshot} stores,
 * {@code GET /v1/catalog/cards/{slug}} returns and {@code catalog.card.published} carries. Same keys and order as
 * the seed's snapshots, plus {@code merchantId} on rules.
 */
public final class CardSnapshots {
  private CardSnapshots() {}

  /** {@code ruleIds.get(i)} becomes the id of {@code doc.rules().get(i)} (new ids per version). */
  public static Map<String, Object> of(UUID cardId, CardDocument doc, String issuerName, List<UUID> ruleIds) {
    Map<String, Object> s = new LinkedHashMap<>();
    s.put("id", cardId.toString());
    s.put("slug", doc.slug());
    s.put("name", doc.name());
    s.put("issuerCode", doc.issuerCode());
    s.put("issuerName", issuerName);
    s.put("network", doc.network());
    s.put("tier", doc.tier());
    s.put("joiningFeePaise", doc.joiningFeePaise());
    s.put("annualFeePaise", doc.annualFeePaise());
    s.put("feeWaiverSpendPaise", doc.feeWaiverSpendPaise());
    s.put("aprBps", doc.aprBps());
    s.put("forexMarkupBps", doc.forexMarkupBps());
    s.put("fuelSurchargeWaiverBps", doc.fuelSurchargeWaiverBps());
    s.put("features", doc.features());
    List<Map<String, Object>> rules = new ArrayList<>();
    for (int i = 0; i < doc.rules().size(); i++) {
      CardDocument.Rule r = doc.rules().get(i);
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("id", ruleIds.get(i).toString());
      m.put("categoryPath", r.categoryPath());
      m.put("merchantId", r.merchantId() == null ? null : r.merchantId().toString());
      m.put("merchantSlug", r.merchantSlug());
      m.put("rewardType", r.rewardType());
      m.put("rateBps", r.rateBps());
      m.put("pointValueMilliPaise", r.pointValueMilliPaise());
      m.put("capPaise", r.capPaise());
      m.put("capPeriod", r.capPeriod());
      m.put("minTxnPaise", r.minTxnPaise());
      m.put("excludedPaths", r.excludedPaths());
      m.put("priority", r.priority());
      rules.add(m);
    }
    s.put("rules", rules);
    if (doc.milestone() == null) {
      s.put("milestone", null);
    } else {
      Map<String, Object> ms = new LinkedHashMap<>();
      ms.put("spendThresholdPaise", doc.milestone().spendThresholdPaise());
      ms.put("period", doc.milestone().period());
      ms.put("benefitType", doc.milestone().benefitType());
      ms.put("benefitValuePaise", doc.milestone().benefitValuePaise());
      s.put("milestone", ms);
    }
    return s;
  }
}
