package in.dexter.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class LedgerDomainTest {
  static final LocalDate D1 = LocalDate.parse("2026-10-16");
  static final LocalDate D2 = LocalDate.parse("2026-10-17");

  @Test
  void deltasForEveryKindOfWrite() {
    DailyDelta.Counted a = new DailyDelta.Counted(D1, "food", 500);
    assertThat(DailyDelta.between(null, a)).containsExactly(new DailyDelta.Change(D1, "food", 500, 1));
    assertThat(DailyDelta.between(a, null)).containsExactly(new DailyDelta.Change(D1, "food", -500, -1));
    assertThat(DailyDelta.between(a, a)).as("no-op").isEmpty();
    assertThat(DailyDelta.between(a, new DailyDelta.Counted(D1, "food", 700)))
        .as("amount only: one bucket, no count change").containsExactly(new DailyDelta.Change(D1, "food", 200, 0));
    assertThat(DailyDelta.between(a, new DailyDelta.Counted(D2, "food", 500)))
        .as("moved day").containsExactly(new DailyDelta.Change(D1, "food", -500, -1),
            new DailyDelta.Change(D2, "food", 500, 1));
    assertThat(DailyDelta.between(a, new DailyDelta.Counted(D1, "travel", 900)))
        .as("moved category root").containsExactly(new DailyDelta.Change(D1, "food", -500, -1),
            new DailyDelta.Change(D1, "travel", 900, 1));
    assertThat(DailyDelta.between(null, null)).isEmpty();
  }

  @Test
  void categoryPaths() {
    assertThat(CategoryPaths.isValid("food")).isTrue();
    assertThat(CategoryPaths.isValid("food.dining.street_food")).isTrue();
    assertThat(CategoryPaths.isValid("a.b.c.d.e")).as("5 levels").isFalse();
    for (String bad : new String[] {null, "", "Food", "food.", ".food", "food..x", "1food", "food-x", "food dining"}) {
      assertThat(CategoryPaths.isValid(bad)).as(String.valueOf(bad)).isFalse();
    }
    assertThat(CategoryPaths.root("food.dining")).isEqualTo("food");
    assertThat(CategoryPaths.root("general")).isEqualTo("general");
    assertThat(CategoryPaths.slug("Pet care & vet!")).isEqualTo("pet_care_vet");
    assertThat(CategoryPaths.slug("Café")).isEqualTo("cafe");
    assertThat(CategoryPaths.slug("2-wheeler")).isEqualTo("c_2_wheeler");
    assertThat(CategoryPaths.slug("!!!")).isNull();
    assertThat(CategoryPaths.levels("food.dining")).isEqualTo(2);
  }

  @Test
  void daysAreIndianDates() {
    assertThat(LedgerTime.day(Instant.parse("2026-10-16T18:29:59Z"))).isEqualTo(D1);
    assertThat(LedgerTime.day(Instant.parse("2026-10-16T18:30:00Z"))).as("midnight IST").isEqualTo(D2);
    assertThat(PaymentMethod.parse("upi")).isEqualTo(PaymentMethod.UPI);
    assertThat(PaymentMethod.parse("UPI")).isNull();
    assertThat(List.of(PaymentMethod.values())).hasSize(6);
  }
}
