package in.dexter.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** P08 pure rules: descriptor normalisation, edit distance, CSV row parsing, derived client references. */
class MerchantTextTest {

  @Test
  void descriptorsReduceToTheMerchant() {
    assertThat(MerchantText.normalize("UPI/432739489763/THEBOMBAYCANTEEN/thebombaycanteen59@axl").text())
        .isEqualTo("thebombaycanteen");
    assertThat(MerchantText.normalize("POS 633108 VISTARA").text()).isEqualTo("vistara");
    assertThat(MerchantText.normalize("ZERODHA*22626260").text()).isEqualTo("zerodha");
    assertThat(MerchantText.normalize("NB/TATAPOWER/1234567890").text()).isEqualTo("tatapower");
    assertThat(MerchantText.normalize("WALLET*FABINDIA").text()).isEqualTo("fabindia");
    assertThat(MerchantText.normalize("H&M MUMBAI").variants()).containsExactly("h and m mumbai", "h and m");
    assertThat(MerchantText.normalize("Air India").variants()).containsExactly("air india");
    assertThat(MerchantText.normalize("Acme Pvt Ltd").variants()).contains("acme");
    assertThat(MerchantText.normalize("WALLET*IRCTCONLINE").compacts()).containsExactly("irctconline", "irctc");
    assertThat(MerchantText.normalize("KFC ONLINE MUMBAI").variants()).containsExactly("kfc online mumbai",
        "kfc online", "kfc");
    assertThat(MerchantText.normalize("UPI/123456789012/ANYTIMEFITNESSSTORE1/x@ybl").maybeTruncated()).isTrue();
    assertThat(MerchantText.normalize("   ").isEmpty()).isTrue();
    assertThat(MerchantText.basic("Domino's Pizza")).isEqualTo("dominos pizza");
  }

  @Test
  void editDistanceCountsTranspositionsAsOne() {
    assertThat(EditDistance.osa("zerohda", "zerodha")).isEqualTo(1);
    assertThat(EditDistance.osa("blnikit", "blinkit")).isEqualTo(1);
    assertThat(EditDistance.osa("practo", "prcato")).isEqualTo(1);
    assertThat(EditDistance.osa("practo", "pratco")).isEqualTo(1);
    assertThat(EditDistance.osa("kitten", "sitting")).isEqualTo(3);
    assertThat(EditDistance.osa("", "abc")).isEqualTo(3);
    assertThat(EditDistance.allowed("jiobp")).isEqualTo(1);
    assertThat(EditDistance.allowed("zerodha")).isEqualTo(2);
  }

  @Test
  void importRowsParseIndianFormats() {
    ImportRow.Result r = ImportRow.parse("14/09/2026", "₹1,23,456.50", " Swiggy ", "FOOD.Delivery", "UPI", "", null);
    assertThat(r.ok()).isTrue();
    assertThat(r.row().occurredAt()).isEqualTo(Instant.parse("2026-09-14T06:30:00Z")); // noon IST
    assertThat(r.row().amountPaise()).isEqualTo(12_345_650);
    assertThat(r.row().merchant()).isEqualTo("Swiggy");
    assertThat(r.row().category()).isEqualTo("food.delivery");
    assertThat(r.row().paymentMethod()).isEqualTo("upi");
    assertThat(r.row().note()).isNull();
    assertThat(ImportRow.parse("2026-09-14", "Rs. 99", null, null, null, null, null).row().paymentMethod())
        .isEqualTo("other");
    assertThat(ImportRow.parse("2026-09-14T10:15:00+05:30", "1", null, null, null, null, null).row().occurredAt())
        .isEqualTo(Instant.parse("2026-09-14T04:45:00Z"));
    for (List<String> bad : List.of(List.of("2026-13-01", "1"), List.of("x", "1"), List.of("2026-01-01", "-1"),
        List.of("2026-01-01", "1.234"), List.of("2026-01-01", "0"), List.of("2026-01-01", ""))) {
      assertThat(ImportRow.parse(bad.get(0), bad.get(1), null, null, null, null, null).ok()).as(bad.toString())
          .isFalse();
    }
    assertThat(ImportRow.parse("2026-01-01", "1", null, "Not A Path!", null, null, null).code())
        .isEqualTo("CATEGORY_INVALID");
    assertThat(ImportRow.parse("2026-01-01", "1", null, null, "cheque", null, null).code())
        .isEqualTo("PAYMENT_METHOD_INVALID");
  }

  @Test
  void derivedReferencesAreStableAndCountIdenticalRows() {
    ImportRow.Parsed p = ImportRow.parse("2026-01-01", "10", "A", null, "upi", null, null).row();
    assertThat(ImportRow.derivedRef(p, 1)).isEqualTo(ImportRow.derivedRef(p, 1)).startsWith("imp-").hasSize(36);
    assertThat(ImportRow.derivedRef(p, 1)).isNotEqualTo(ImportRow.derivedRef(p, 2));
    ImportRow.Parsed q = ImportRow.parse("2026-01-01", "10.01", "A", null, "upi", null, null).row();
    assertThat(ImportRow.derivedRef(q, 1)).isNotEqualTo(ImportRow.derivedRef(p, 1));
  }

  @Test
  void dailyDeltaSumMergesBuckets() {
    var day = java.time.LocalDate.of(2026, 1, 1);
    assertThat(DailyDelta.sum(List.of(new DailyDelta.Counted(day, "food", 100), new DailyDelta.Counted(day, "food", 50),
        new DailyDelta.Counted(day, "travel", 7)))).containsExactly(new DailyDelta.Change(day, "food", 150, 2),
        new DailyDelta.Change(day, "travel", 7, 1));
  }
}
