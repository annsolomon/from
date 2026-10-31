package in.dexter.reco.app;

import in.dexter.reco.domain.CardTerms;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Projection rows to the engine's {@link CardTerms}. The milestone is the one piece still stored as JSON (it has
 * four fields and no query reads inside it), so this is where it is parsed — the domain never sees Jackson.
 */
final class CardTermsMapper {
  private CardTermsMapper() {}

  static CardTerms of(RecoStore.CardRow card, List<RecoStore.RuleRow> rules, ObjectMapper mapper) {
    List<CardTerms.Rule> out = new ArrayList<>(rules.size());
    for (RecoStore.RuleRow r : rules) {
      out.add(new CardTerms.Rule(r.ruleId().toString(), r.categoryPath(),
          r.merchantId() == null ? null : r.merchantId().toString(), r.rewardType(), r.rateBps(),
          r.pointValueMilliPaise(), r.capPaise(), r.capPeriod(), r.minTxnPaise(), r.excludedPaths(),
          r.priority()));
    }
    return new CardTerms(card.cardProductId().toString(), card.slug(), card.name(), card.issuerCode(),
        card.annualFeePaise(), card.feeWaiverSpendPaise(), out, milestone(card.milestoneJson(), mapper));
  }

  private static CardTerms.Milestone milestone(String json, ObjectMapper mapper) {
    if (json == null || json.isBlank() || "null".equals(json)) {
      return null;
    }
    JsonNode m = mapper.readTree(json);
    long threshold = num(m, "spendThresholdPaise");
    String period = text(m, "period");
    if (threshold <= 0 || period == null) {
      return null;   // a snapshot without a usable milestone scores as having none
    }
    return new CardTerms.Milestone(threshold, period, text(m, "benefitType"), num(m, "benefitValuePaise"));
  }

  private static String text(JsonNode n, String f) {
    JsonNode v = n.get(f);
    return v == null || v.isNull() ? null : v.asString();
  }

  private static long num(JsonNode n, String f) {
    JsonNode v = n.get(f);
    return v == null || v.isNull() || !v.isNumber() ? 0 : v.asLong();
  }
}
