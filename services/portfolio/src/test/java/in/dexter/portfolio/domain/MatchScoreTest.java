package in.dexter.portfolio.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Merchant agreement and the match score (pure). */
class MatchScoreTest {

  @Test
  void bankDescriptorsAgreeWithTheMerchantName() {
    assertThat(MatchScore.normalize("SWIGGY*ORDER 88123 BANGALORE IN")).isEqualTo("swiggy");
    assertThat(MatchScore.merchant("Swiggy", null, "SWIGGY*88123004", null)).isEqualTo(1.0);
    assertThat(MatchScore.merchant("Swiggy", null, "POS 123456 SWIGGY", null)).isEqualTo(1.0);
    assertThat(MatchScore.merchant("Big Basket", null, "BIGBASKET.COM", null)).isEqualTo(1.0);
    assertThat(MatchScore.merchant("H&M India", null, "POS 452392 H&M INDIA", null)).isEqualTo(1.0);
    assertThat(MatchScore.merchant("HP Pay", null, "HPPAY*33473058", null)).isEqualTo(1.0);
    assertThat(MatchScore.merchant("Hyderabad Metro Rail", null, "HYDERABADMETRORAIL*0", null))
        .as("a 20-character truncated descriptor").isEqualTo(1.0);
    assertThat(MatchScore.merchant("Amazon India", null, "AMAZON PAY INDIA PVT LTD", null))
        .as("a gateway word in between: trigram").isGreaterThan(MatchScore.MIN_MERCHANT);
    assertThat(MatchScore.merchant("Swiggy", null, "SWIGY BANGALORE", null)).as("a typo")
        .isGreaterThan(MatchScore.MIN_MERCHANT);
    UUID m = UUID.randomUUID();
    assertThat(MatchScore.merchant(null, m, "anything", m)).as("same resolved merchant").isEqualTo(1.0);
  }

  @Test
  void differentMerchantsDoNotAgree() {
    assertThat(MatchScore.merchant("Zomato", null, "SWIGGY BANGALORE", null)).isLessThan(MatchScore.MIN_MERCHANT);
    assertThat(MatchScore.merchant("Uber", null, "OLA CABS MUMBAI", null)).isLessThan(MatchScore.MIN_MERCHANT);
    assertThat(MatchScore.merchant("Hyderabad Metro", null, "KOLKATA METRO PUNE", null))
        .as("a shared word is not the merchant").isLessThan(MatchScore.MIN_MERCHANT);
    assertThat(MatchScore.merchant("LIC", null, "DELHI PUBLIC SCHOOL", null)).as("only at a word start")
        .isLessThan(MatchScore.MIN_MERCHANT);
    assertThat(MatchScore.merchant("", null, "SWIGGY", null)).isZero();
    assertThat(MatchScore.merchant("Swiggy", null, "", null)).isZero();
  }

  @Test
  void scoreWeighsMerchantAndDays() {
    assertThat(MatchScore.score(1.0, 0)).isEqualTo(1.0);
    assertThat(MatchScore.score(1.0, -2)).isEqualTo(0.833);
    assertThat(MatchScore.score(0.6, 1)).isEqualTo(Math.round((0.45 + 0.25 * 2 / 3.0) * 1000) / 1000.0);
    assertThat(MatchScore.score(1.0, 3)).as("outside ±2 days").isEqualTo(-1);
    assertThat(MatchScore.score(0.2, 0)).as("merchants disagree").isEqualTo(-1);
  }
}
