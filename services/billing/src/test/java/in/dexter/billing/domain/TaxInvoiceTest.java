package in.dexter.billing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** The mock GST split (ADR-0022 §6): 18 % computed once, CGST + SGST inside the supplier's state, IGST outside. */
class TaxInvoiceTest {

  @Test
  void premiumInsideTheSupplierStateIsCgstPlusSgst() {
    TaxInvoice.Result r = TaxInvoice.of("Dexter Premium - 1 month", 19_900, "KA", "KA");
    assertThat(r.regime()).isEqualTo("intra");
    assertThat(r.subtotalPaise()).isEqualTo(19_900);
    assertThat(r.taxPaise()).isEqualTo(3_582);       // 18 % of Rs 199.00 = Rs 35.82
    assertThat(r.totalPaise()).isEqualTo(23_482);
    assertThat(r.lines()).extracting(TaxInvoice.Line::kind)
        .containsExactly(TaxInvoice.Kind.ITEM, TaxInvoice.Kind.CGST, TaxInvoice.Kind.SGST);
    assertThat(r.lines()).extracting(TaxInvoice.Line::amountPaise).containsExactly(19_900L, 1_791L, 1_791L);
    assertThat(r.lines()).extracting(TaxInvoice.Line::rateBps).containsExactly(null, 900, 900);
  }

  @Test
  void anotherStateIsIgstForTheSameTotal() {
    TaxInvoice.Result intra = TaxInvoice.of("x", 49_900, "KA", "KA");
    TaxInvoice.Result inter = TaxInvoice.of("x", 49_900, "KA", "MH");
    assertThat(inter.regime()).isEqualTo("inter");
    assertThat(inter.lines()).extracting(TaxInvoice.Line::kind).containsExactly(TaxInvoice.Kind.ITEM,
        TaxInvoice.Kind.IGST);
    assertThat(inter.lines().get(1).rateBps()).isEqualTo(1800);
    assertThat(inter.taxPaise()).isEqualTo(8_982).isEqualTo(intra.taxPaise());
    assertThat(inter.totalPaise()).isEqualTo(58_882).isEqualTo(intra.totalPaise());
  }

  @Test
  void anOddPaisaOfTaxGoesToCgstAndTheHalvesStillAddUp() {
    // 18 % of 19 999 = 3599.82 -> 3600; of 10 003 = 1800.54 -> 1801 (odd)
    TaxInvoice.Result r = TaxInvoice.of("x", 10_003, "TN", "TN");
    assertThat(r.taxPaise()).isEqualTo(1_801);
    assertThat(r.lines().get(1).amountPaise()).isEqualTo(901);
    assertThat(r.lines().get(2).amountPaise()).isEqualTo(900);
    assertThat(r.lines().get(1).amountPaise() + r.lines().get(2).amountPaise()).isEqualTo(r.taxPaise());
    for (long p = 0; p < 5_000; p += 7) {
      TaxInvoice.Result x = TaxInvoice.of("x", p, "KA", "KA");
      assertThat(x.lines().get(1).amountPaise() + x.lines().get(2).amountPaise()).isEqualTo(x.taxPaise());
      assertThat(x.totalPaise()).isEqualTo(TaxInvoice.of("x", p, "KA", "DL").totalPaise());
    }
  }

  @Test
  void unknownStatesAndNegativeAmountsAreRefused() {
    assertThatThrownBy(() -> TaxInvoice.of("x", 100, "KA", "XX")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> TaxInvoice.gstOn(-1)).isInstanceOf(IllegalArgumentException.class);
    assertThat(IndianStates.CODES).hasSize(36).contains("KA", "MH", "DL", "TN", "LA");
  }
}
