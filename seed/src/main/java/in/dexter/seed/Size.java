package in.dexter.seed;

import java.util.Locale;

/**
 * Dataset sizes (MASTER_PLAN 11.1). Every size contains the two demo accounts (500 expenses each) and the admin;
 * the rest are bulk accounts. Totals are exact: {@code medium} has exactly 10,000 accounts, 1,200,000 expenses and
 * 30,000 user cards.
 */
public record Size(String name, int accounts, long expenses, int userCards) {
  public static final int DEMO_ACCOUNTS = 2;
  public static final int DEMO_EXPENSES_EACH = 500;
  public static final int DEMO_CARDS = 5; // asha 3 + ravi 2
  /** Bulk accounts never get more cards than this. */
  public static final int MAX_CARDS_PER_ACCOUNT = 6;

  public static final Size SMALL = new Size("small", 2, 1_000, 5);
  public static final Size MEDIUM = new Size("medium", 10_000, 1_200_000, 30_000);
  public static final Size LARGE = new Size("large", 100_000, 5_000_000, 300_000);

  public Size {
    if (accounts < DEMO_ACCOUNTS) {
      throw new IllegalArgumentException("a dataset holds at least the " + DEMO_ACCOUNTS + " demo accounts");
    }
    if (expenses < (long) DEMO_ACCOUNTS * DEMO_EXPENSES_EACH || userCards < DEMO_CARDS) {
      throw new IllegalArgumentException("a dataset holds at least the demo expenses and cards");
    }
    long bulk = accounts - DEMO_ACCOUNTS;
    if (userCards - DEMO_CARDS > bulk * MAX_CARDS_PER_ACCOUNT) {
      throw new IllegalArgumentException("too many cards for " + bulk + " bulk accounts");
    }
    if (bulk == 0 && (expenses > (long) DEMO_ACCOUNTS * DEMO_EXPENSES_EACH || userCards > DEMO_CARDS)) {
      throw new IllegalArgumentException("bulk expenses/cards need bulk accounts");
    }
  }

  public int bulkAccounts() {
    return accounts - DEMO_ACCOUNTS;
  }

  public long bulkExpenses() {
    return expenses - (long) DEMO_ACCOUNTS * DEMO_EXPENSES_EACH;
  }

  public int bulkCards() {
    return userCards - DEMO_CARDS;
  }

  /** {@code small|medium|large} or {@code custom:<accounts>,<expenses>,<userCards>} (tests, quick runs). */
  public static Size parse(String s) {
    String v = s.toLowerCase(Locale.ROOT);
    return switch (v) {
      case "small" -> SMALL;
      case "medium" -> MEDIUM;
      case "large" -> LARGE;
      default -> {
        if (!v.startsWith("custom:")) {
          throw new IllegalArgumentException("size must be small|medium|large|custom:A,E,C, got " + s);
        }
        String[] p = v.substring("custom:".length()).split(",");
        if (p.length != 3) {
          throw new IllegalArgumentException("custom size is custom:<accounts>,<expenses>,<userCards>");
        }
        yield new Size(v, Integer.parseInt(p[0]), Long.parseLong(p[1]), Integer.parseInt(p[2]));
      }
    };
  }
}
