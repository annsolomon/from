package in.dexter.planning.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What-if evaluation (MASTER_PLAN P13). A scenario is evaluated against the snapshot taken when it was saved, never
 * a live view, so it answers the same question tomorrow as it did today; the adjustments are applied to that copy.
 *
 * <p>Pure, and deliberately plain arithmetic on monthly figures: budget limits plus liability instalments against
 * income. A new liability's instalment comes from the shared EMI engine ({@link LiabilityMath}), so a hypothetical
 * loan in a scenario and a real one recorded later agree to the paisa.
 *
 * <p>Ids are the strings the stored JSON carries; the app layer owns JSON (the domain stays free of it).
 */
public final class ScenarioMath {

  private ScenarioMath() {}

  public record BudgetLine(String id, String name, long limitPaise) {}

  public record LiabilityLine(String id, String lender, long emiPaise) {}

  /** The copied-in starting point. */
  public record Snapshot(long monthlyIncomePaise, List<BudgetLine> budgets, List<LiabilityLine> liabilities) {
    public Snapshot {
      budgets = budgets == null ? List.of() : List.copyOf(budgets);
      liabilities = liabilities == null ? List.of() : List.copyOf(liabilities);
      if (monthlyIncomePaise < 0) {
        throw new IllegalArgumentException("monthly income must not be negative");
      }
    }
  }

  /** The four things a scenario can change. Anything else is refused rather than ignored. */
  public sealed interface Adjustment {

    record SetMonthlyIncome(long amountPaise) implements Adjustment {
      public SetMonthlyIncome {
        if (amountPaise < 0) {
          throw new IllegalArgumentException("monthly income must not be negative");
        }
      }
    }

    record SetBudgetLimit(String budgetId, long limitPaise) implements Adjustment {
      public SetBudgetLimit {
        if (budgetId == null || budgetId.isBlank() || limitPaise < 0) {
          throw new IllegalArgumentException("setBudgetLimit needs a budgetId and a limit of zero or more");
        }
      }
    }

    record AddLiability(String lender, long principalPaise, int aprBps, int tenureMonths) implements Adjustment {
      public AddLiability {
        if (lender == null || lender.isBlank() || principalPaise <= 0 || aprBps < 0 || tenureMonths < 1) {
          throw new IllegalArgumentException("addLiability needs a lender, a positive principal, a rate and a tenure");
        }
      }
    }

    record RemoveLiability(String liabilityId) implements Adjustment {
      public RemoveLiability {
        if (liabilityId == null || liabilityId.isBlank()) {
          throw new IllegalArgumentException("removeLiability needs a liabilityId");
        }
      }
    }
  }

  /** @param kind {@code budget} or {@code liability} */
  public record Line(String kind, String id, String label, long monthlyPaise, boolean added) {}

  /**
   * @param monthlyIncomePaise the income the scenario assumes, which an adjustment may have changed
   * @param surplusBeforePaise the snapshot's own surplus: its income against its outgo, both unadjusted
   * @param surplusAfterPaise the scenario's surplus: the assumed income against the adjusted outgo
   * @param feasible whether income still covers the outgo after the adjustments
   * @param lines every budget and liability as the scenario leaves them
   */
  public record Result(long monthlyIncomePaise, long monthlyOutgoBeforePaise, long monthlyOutgoAfterPaise,
      long deltaPaise, long surplusBeforePaise, long surplusAfterPaise, boolean feasible, List<Line> lines) {}

  public static Result evaluate(Snapshot base, List<Adjustment> adjustments) {
    long income = base.monthlyIncomePaise();
    Map<String, BudgetLine> budgets = new LinkedHashMap<>();
    for (BudgetLine b : base.budgets()) {
      budgets.put(b.id(), b);
    }
    Map<String, LiabilityLine> liabilities = new LinkedHashMap<>();
    for (LiabilityLine l : base.liabilities()) {
      liabilities.put(l.id(), l);
    }
    long outgoBefore = total(budgets.values(), BudgetLine::limitPaise) + total(liabilities.values(),
        LiabilityLine::emiPaise);
    List<String> added = new ArrayList<>();

    int hypothetical = 0;
    for (Adjustment a : adjustments == null ? List.<Adjustment>of() : adjustments) {
      switch (a) {
        case Adjustment.SetMonthlyIncome s -> income = s.amountPaise();
        case Adjustment.SetBudgetLimit s -> {
          BudgetLine existing = budgets.get(s.budgetId());
          if (existing == null) {
            throw new IllegalArgumentException("the snapshot has no budget " + s.budgetId());
          }
          budgets.put(s.budgetId(), new BudgetLine(existing.id(), existing.name(), s.limitPaise()));
        }
        case Adjustment.RemoveLiability r -> {
          if (liabilities.remove(r.liabilityId()) == null) {
            throw new IllegalArgumentException("the snapshot has no liability " + r.liabilityId());
          }
        }
        case Adjustment.AddLiability n -> {
          String id = "hypothetical-" + (++hypothetical);
          liabilities.put(id, new LiabilityLine(id, n.lender(),
              LiabilityMath.emi(n.principalPaise(), n.aprBps(), n.tenureMonths())));
          added.add(id);
        }
      }
    }

    long outgoAfter = total(budgets.values(), BudgetLine::limitPaise) + total(liabilities.values(),
        LiabilityLine::emiPaise);
    List<Line> lines = new ArrayList<>(budgets.size() + liabilities.size());
    budgets.values().forEach(b -> lines.add(new Line("budget", b.id(), b.name(), b.limitPaise(), false)));
    liabilities.values().forEach(l -> lines.add(new Line("liability", l.id(), l.lender(), l.emiPaise(),
        added.contains(l.id()))));

    // "before" is the snapshot as it was saved, income included: otherwise changing the income would silently
    // restate the starting point the scenario is being compared against.
    return new Result(income, outgoBefore, outgoAfter, outgoAfter - outgoBefore,
        base.monthlyIncomePaise() - outgoBefore, income - outgoAfter, income - outgoAfter >= 0,
        List.copyOf(lines));
  }

  private static <T> long total(Iterable<T> rows, java.util.function.ToLongFunction<T> of) {
    long sum = 0;
    for (T row : rows) {
      sum += of.applyAsLong(row);
    }
    return sum;
  }
}
