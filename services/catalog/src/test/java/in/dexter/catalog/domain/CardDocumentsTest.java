package in.dexter.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.core.error.FieldError;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Card document validation, normalisation and the published snapshot shape (pure). */
class CardDocumentsTest {
  static CardDocument doc(List<CardDocument.Rule> rules) {
    return new CardDocument("mock-card", "Mock Card", "MOCK_BANK", "visa", "entry", 50_000L, 50_000L, null, 4200, 350,
        null, Map.of("zeta", 1, "alpha", true), rules, null);
  }

  static CardDocument.Rule rule(String path, Integer rate) {
    return new CardDocument.Rule(path, null, null, "cashback", rate, null, null, null, null, null, null);
  }

  @Test
  void aValidDocumentNormalisesDefaultsAndSortsFeatures() {
    CardDocument d = doc(List.of(rule(null, 100), rule("food.delivery", 500)));
    assertThat(CardDocuments.validate(d)).isEmpty();
    CardDocument n = CardDocuments.normalize(d);
    assertThat(n.features().keySet()).containsExactly("alpha", "zeta");
    assertThat(n.rules().get(0).priority()).isEqualTo(100);
    assertThat(n.rules().get(0).minTxnPaise()).isZero();
    assertThat(n.rules().get(0).excludedPaths()).isEmpty();
  }

  @Test
  void invalidFieldsAreReportedByName() {
    CardDocument bad = new CardDocument("Bad!", "", null, "paypal", "", -1L, null, null, 20_000, null, null,
        Map.of("Bad Key", 1), List.of(rule("food", 100), rule("food", 200), rule("Bad.Path", -1)),
        new CardDocument.Milestone(0L, "decade", "", -5L));
    List<String> fields = CardDocuments.validate(bad).stream().map(FieldError::field).toList();
    assertThat(fields).contains("slug", "name", "issuerCode", "network", "tier", "joiningFeePaise", "aprBps",
        "features.Bad Key", "rules[1].categoryPath", "rules[2].categoryPath", "rules[2].rateBps",
        "milestone.spendThresholdPaise", "milestone.period", "milestone.benefitType", "milestone.benefitValuePaise");
    CardDocument cap = doc(List.of(new CardDocument.Rule(null, null, null, "points", 100, 25_000L, 1000L, null, 0L,
        null, 1)));
    assertThat(CardDocuments.validate(cap)).extracting(FieldError::code).containsExactly("CAP_SHAPE");
  }

  @Test
  void sameCategoryWithDifferentMerchantsIsNotADuplicateScope() {
    UUID m = UUID.randomUUID();
    CardDocument d = doc(List.of(rule("food", 100),
        new CardDocument.Rule("food", m, "swiggy", "cashback", 500, null, null, null, null, null, null)));
    assertThat(CardDocuments.validate(d)).isEmpty();
  }

  @Test
  void theSnapshotKeepsTheSeedShapePlusMerchantIds() {
    UUID card = UUID.randomUUID();
    UUID rid = UUID.randomUUID();
    CardDocument d = CardDocuments.normalize(doc(List.of(rule("food", 500))));
    Map<String, Object> s = CardSnapshots.of(card, d, "Mock Bank", List.of(rid));
    assertThat(s.keySet()).containsExactly("id", "slug", "name", "issuerCode", "issuerName", "network", "tier",
        "joiningFeePaise", "annualFeePaise", "feeWaiverSpendPaise", "aprBps", "forexMarkupBps",
        "fuelSurchargeWaiverBps", "features", "rules", "milestone");
    @SuppressWarnings("unchecked")
    Map<String, Object> r = ((List<Map<String, Object>>) s.get("rules")).getFirst();
    assertThat(r.keySet()).containsExactly("id", "categoryPath", "merchantId", "merchantSlug", "rewardType", "rateBps",
        "pointValueMilliPaise", "capPaise", "capPeriod", "minTxnPaise", "excludedPaths", "priority");
    assertThat(r.get("id")).isEqualTo(rid.toString());
    assertThat(s.get("milestone")).isNull();
  }
}
