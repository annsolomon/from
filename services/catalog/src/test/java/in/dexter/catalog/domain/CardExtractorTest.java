package in.dexter.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Extractor pattern-v1 on an issuer-style page and on the wording variants Indian issuers use (pure). */
class CardExtractorTest {

  static String fixture(String name) {
    try (InputStream in = CardExtractorTest.class.getResourceAsStream("/ingestion/" + name)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  static Object value(CardExtractor.Extraction x, String field) {
    return x.fields().get(field).value();
  }

  @Test
  void readsFeesRatesAndCashbackFromAnIssuerPage() {
    CardExtractor.Extraction x = CardExtractor.extract(fixture("saffron-millennia.html"));
    assertThat(x.name()).isEqualTo("MOCK_Saffron Millennia Cashback");
    assertThat(x.fields().keySet()).containsExactlyElementsOf(CardExtractor.FIELDS);
    assertThat(value(x, "joiningFeePaise")).isEqualTo(100_000L);
    assertThat(value(x, "annualFeePaise")).as("'Rs. 1,500'; the ₹ 99999 inside <script> is ignored").isEqualTo(150_000L);
    assertThat(value(x, "feeWaiverSpendPaise")).as("₹1 lakh").isEqualTo(10_000_000L);
    assertThat(value(x, "forexMarkupBps")).isEqualTo(350);
    assertThat(value(x, "aprBps")).as("3.6 % per month = 43.2 % a year").isEqualTo(4320);
    assertThat(value(x, "fuelSurchargeWaiverBps")).isEqualTo(100);
    assertThat(x.rules()).extracting(CardExtractor.RuleHit::categoryPath, CardExtractor.RuleHit::rateBps)
        .containsExactly(org.assertj.core.groups.Tuple.tuple("shopping.online", 500),
            org.assertj.core.groups.Tuple.tuple("food.delivery", 700),
            org.assertj.core.groups.Tuple.tuple(null, 100));
    assertThat(x.unmapped()).containsExactly("gold coins");
    assertThat(x.confidence()).isEqualTo(1.0);
  }

  @Test
  void conflictingStatementsLowerTheConfidenceAndTheFirstWins() {
    CardExtractor.Extraction x = CardExtractor.extract("<h1>Twin</h1><p>Annual fee: ₹500</p><p>Annual fee: ₹999</p>"
        + "<p>2% cashback on dining</p><p>3% cashback on restaurants</p>");
    assertThat(value(x, "annualFeePaise")).isEqualTo(50_000L);
    assertThat(x.fields().get("annualFeePaise").confidence()).isEqualTo(0.5);
    assertThat(x.rules()).singleElement().satisfies(r -> {
      assertThat(r.categoryPath()).isEqualTo("food.dining");
      assertThat(r.rateBps()).isEqualTo(200);
      assertThat(r.confidence()).isEqualTo(0.5);
    });
    assertThat(x.confidence()).isEqualTo(0.5);
  }

  @Test
  void wordingVariants() {
    CardExtractor.Extraction free = CardExtractor.extract("<title>Zero Card</title><p>A lifetime free card.</p>"
        + "<p>Interest at 42% per annum.</p><p>Spend ₹2.5 lakhs in a card year to get the annual fee waived.</p>");
    assertThat(value(free, "joiningFeePaise")).isEqualTo(0L);
    assertThat(value(free, "annualFeePaise")).isEqualTo(0L);
    assertThat(free.fields().get("annualFeePaise").confidence()).isEqualTo(0.8);
    assertThat(value(free, "aprBps")).isEqualTo(4200);
    assertThat(value(free, "feeWaiverSpendPaise")).isEqualTo(25_000_000L);

    CardExtractor.Extraction nil = CardExtractor.extract("<p>Joining fee: Nil. Renewal fee - INR 2,999 + GST</p>");
    assertThat(value(nil, "joiningFeePaise")).isEqualTo(0L);
    assertThat(value(nil, "annualFeePaise")).isEqualTo(299_900L);
  }

  @Test
  void aPageWithoutCardFactsIsEmpty() {
    CardExtractor.Extraction x = CardExtractor.extract("<html><title>About us</title><p>We are a bank.</p></html>");
    assertThat(x.isEmpty()).isTrue();
    assertThat(x.confidence()).isZero();
    assertThat(CardExtractor.extract("<p>50% cashback on everything else</p>").rules())
        .as("an implausible rate (> 25 %) is ignored").isEmpty();
  }

  @Test
  void amountsAreExactPaise() {
    assertThat(CardExtractor.paise("1,00,000", null)).isEqualTo(10_000_000L);
    assertThat(CardExtractor.paise("1.5", "lakh")).isEqualTo(15_000_000L);
    assertThat(CardExtractor.paise("2", "crore")).isEqualTo(2_000_000_000L);
    assertThat(CardExtractor.paise("499.99", null)).isEqualTo(49_999L);
    assertThat(CardExtractor.bps("3.49", 1200)).isEqualTo(4188);
    assertThat(CardExtractor.category("all other spends except fuel")).isEqualTo(CardExtractor.BASE);
    assertThat(CardExtractor.category("online shopping on amazon")).isEqualTo("shopping.online");
    assertThat(CardExtractor.category("gold coins")).isNull();
    assertThat(HtmlText.text("<p>a&nbsp;&amp;&#8377;b</p><!-- ₹1 --><style>x</style>")).isEqualTo("a &₹b\n");
    assertThat(List.of(HtmlText.title("<meta property='og:title' content='OG'><h1>H</h1>"),
        HtmlText.title("<title>T</title><h1>H <b>1</b></h1>"))).containsExactly("OG", "H 1");
  }
}
