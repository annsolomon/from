package in.dexter.reco;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.reco.app.ProjectionService;
import in.dexter.reco.support.Events;
import in.dexter.reco.support.RecoIT;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The spend profile built from ledger events (MASTER_PLAN P14), including the plan's named case: the projection
 * must equal a recomputed sum, and a stale or replayed event must not double-count.
 */
class SpendProfileIT extends RecoIT {
  @Autowired
  ProjectionService projections;

  /** The account's local month, because that is the bucket a day falls in. */
  static String thisMonth() {
    return LocalDate.now(ZoneId.of("Asia/Kolkata")).withDayOfMonth(1).toString();
  }

  static String today() {
    return LocalDate.now(ZoneId.of("Asia/Kolkata")).toString();
  }

  @Test
  void anExpenseBecomesMonthlySpendAndAProfile() {
    UUID a = UUID.randomUUID();
    assertThat(projections.onExpenseEvent(Events.created(a, UUID.randomUUID(),
        Events.state(600_000L, today(), "food.dining")))).isEqualTo(ProjectionService.Outcome.APPLIED);

    assertThat(monthTotal(a, "food", thisMonth())).as("the root, not the full path").isEqualTo(600_000L);
    assertThat(profile(a, "3m")).containsEntry("food", 600_000L);
    assertThat(profile(a, "6m")).containsEntry("food", 600_000L);
    assertThat(profile(a, "12m")).as("one month of spend averages to itself in every window")
        .containsEntry("food", 600_000L);
  }

  @Test
  void aReplayedEventDoesNotDoubleCount() {
    UUID a = UUID.randomUUID();
    var event = Events.created(a, UUID.randomUUID(), Events.state(500_000L, today(), "travel.air"));
    assertThat(projections.onExpenseEvent(event)).isEqualTo(ProjectionService.Outcome.APPLIED);
    assertThat(projections.onExpenseEvent(event)).as("the inbox is the guard, not a version compare")
        .isEqualTo(ProjectionService.Outcome.DUPLICATE);
    assertThat(monthTotal(a, "travel", thisMonth())).isEqualTo(500_000L);
  }

  @Test
  void anEditSettlesBothSidesAndADeleteSubtracts() {
    UUID a = UUID.randomUUID();
    UUID expense = UUID.randomUUID();
    projections.onExpenseEvent(Events.created(a, expense, Events.state(600_000L, today(), "food.dining")));

    // the amount changes
    projections.onExpenseEvent(Events.updated(a, expense, 1, Events.state(600_000L, today(), "food.dining"),
        Events.state(900_000L, today(), "food.dining")));
    assertThat(monthTotal(a, "food", thisMonth())).isEqualTo(900_000L);

    // the category moves to another root: the old root loses it, the new one gains it
    projections.onExpenseEvent(Events.updated(a, expense, 2, Events.state(900_000L, today(), "food.dining"),
        Events.state(900_000L, today(), "travel.cab")));
    assertThat(monthTotal(a, "food", thisMonth())).isZero();
    assertThat(monthTotal(a, "travel", thisMonth())).isEqualTo(900_000L);
    assertThat(profile(a, "3m")).as("a root with no spend left leaves the profile").doesNotContainKey("food");
    assertThat(profile(a, "3m")).containsEntry("travel", 900_000L);

    // and a delete takes it away
    projections.onExpenseEvent(Events.deleted(a, expense, 3, Events.state(900_000L, today(), "travel.cab")));
    assertThat(monthTotal(a, "travel", thisMonth())).isZero();
    assertThat(profile(a, "3m")).isEmpty();
  }

  @Test
  void spendNeverGoesNegative() {
    UUID a = UUID.randomUUID();
    // a delete with no matching create (reco started projecting mid-stream): the row floors at zero
    projections.onExpenseEvent(Events.deleted(a, UUID.randomUUID(), 1,
        Events.state(900_000L, today(), "food.dining")));
    assertThat(monthTotal(a, "food", thisMonth())).isZero();
    assertThat(profile(a, "3m")).isEmpty();
  }

  @Test
  void theProjectionEqualsARecomputedSum() {
    // MASTER_PLAN P14's named test, the same shape as planning's SpendPropertyIT: random operations, then every
    // stored window compared with an independently recomputed average
    UUID a = UUID.randomUUID();
    java.util.Random rnd = new java.util.Random(14);
    String[] roots = {"food", "travel", "shopping"};
    java.util.Map<String, java.util.Map<String, Long>> expected = new java.util.LinkedHashMap<>();
    java.util.List<Object[]> live = new java.util.ArrayList<>();   // {expenseId, amount, root, day, version}

    for (int i = 0; i < 150; i++) {
      boolean create = live.isEmpty() || rnd.nextInt(100) < 60;
      LocalDate day = LocalDate.now(ZoneId.of("Asia/Kolkata")).minusDays(rnd.nextInt(60));
      if (create) {
        UUID id = UUID.randomUUID();
        long amount = (rnd.nextInt(500) + 1) * 100L;
        String root = roots[rnd.nextInt(roots.length)];
        projections.onExpenseEvent(Events.created(a, id, Events.state(amount, day.toString(), root + ".x")));
        add(expected, root, day, amount);
        live.add(new Object[] {id, amount, root, day, 0L});
      } else {
        Object[] e = live.remove(rnd.nextInt(live.size()));
        long version = (long) e[4] + 1;
        projections.onExpenseEvent(Events.deleted(a, (UUID) e[0], version,
            Events.state((long) e[1], ((LocalDate) e[3]).toString(), (String) e[2] + ".x")));
        add(expected, (String) e[2], (LocalDate) e[3], -(long) e[1]);
      }
    }

    for (String root : roots) {
      java.util.Map<String, Long> months = expected.getOrDefault(root, java.util.Map.of());
      for (java.util.Map.Entry<String, Long> m : months.entrySet()) {
        assertThat(monthTotal(a, root, m.getKey()))
            .as("month %s of %s", m.getKey(), root).isEqualTo(Math.max(0, m.getValue()));
      }
    }
    // the 3m window's average is the sum over the months that contributed
    LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
    for (String root : roots) {
      long total = 0;
      int monthsWithSpend = 0;
      for (int back = 0; back < 3; back++) {
        String month = today.withDayOfMonth(1).minusMonths(back).toString();
        long t = monthTotal(a, root, month);
        if (t > 0) {
          total += t;
          monthsWithSpend++;
        }
      }
      long want = monthsWithSpend == 0 ? 0 : total / monthsWithSpend;
      assertThat(profile(a, "3m").getOrDefault(root, 0L)).as("the 3m average of %s", root).isEqualTo(want);
    }
  }

  private static void add(java.util.Map<String, java.util.Map<String, Long>> acc, String root, LocalDate day,
      long delta) {
    acc.computeIfAbsent(root, k -> new java.util.LinkedHashMap<>())
        .merge(day.withDayOfMonth(1).toString(), delta, Long::sum);
  }

  @Test
  void anEventWithoutAnAccountOrAnotherTypeIsIgnored() {
    UUID a = UUID.randomUUID();
    assertThat(projections.onExpenseEvent(Events.card(
        in.dexter.contracts.events.portfolio.CardChangedV1.ADDED, a, UUID.randomUUID(), UUID.randomUUID(),
        "active", 0))).isEqualTo(ProjectionService.Outcome.IGNORED);
  }
}
