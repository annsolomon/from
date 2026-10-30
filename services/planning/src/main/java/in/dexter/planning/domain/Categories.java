package in.dexter.planning.domain;

/**
 * Budget ↔ expense category matching, the same containment Postgres' {@code <@} applies to the stored
 * {@code ltree} (MASTER_PLAN P13): a budget on {@code food} counts an expense categorised {@code food} or anything
 * below it ({@code food.dining}), and a budget with no category counts every expense.
 *
 * <p>The consumer matches in SQL so the database does the work; this is the same rule in Java, for the scenario
 * evaluator and so the tests can state it once and check both.
 */
public final class Categories {

  private Categories() {}

  /** @param budgetPath null means the budget is on total spend */
  public static boolean matches(String budgetPath, String expensePath) {
    if (budgetPath == null || budgetPath.isEmpty()) {
      return true;
    }
    if (expensePath == null) {
      return false;
    }
    return expensePath.equals(budgetPath) || expensePath.startsWith(budgetPath + ".");
  }
}
