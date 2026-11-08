package in.dexter.commerce.domain;

/**
 * One part of a deal's saving (MASTER_PLAN P17). Each component is evaluated against the same spend and capped on its
 * own; {@link Savings} adds them. Money is integer paise; rates are basis points.
 */
public sealed interface SavingComponent permits SavingComponent.Percent, SavingComponent.Flat {
  long MAX_PAISE = 100_000_000_000L;

  /** The component applies only when the spend reaches this (0 = always). */
  long minSpendPaise();

  /** The saving this component alone gives on {@code spendPaise}. */
  long savingFor(long spendPaise);

  /** {@code valueBps} of the spend, at most {@code capPaise}. */
  record Percent(int valueBps, long capPaise, long minSpendPaise) implements SavingComponent {
    public Percent {
      if (valueBps < 1 || valueBps > 10_000) {
        throw new IllegalArgumentException("valueBps must be 1..10000");
      }
      if (capPaise < 1 || capPaise > MAX_PAISE) {
        throw new IllegalArgumentException("capPaise must be 1.." + MAX_PAISE);
      }
      checkMinSpend(minSpendPaise);
    }

    @Override
    public long savingFor(long spendPaise) {
      if (spendPaise < minSpendPaise || spendPaise <= 0) {
        return 0;
      }
      long raw = Math.multiplyExact(spendPaise, (long) valueBps) / 10_000L;
      return Math.min(raw, capPaise);
    }
  }

  /** A fixed amount off, never more than the spend itself. */
  record Flat(long valuePaise, long minSpendPaise) implements SavingComponent {
    public Flat {
      if (valuePaise < 1 || valuePaise > MAX_PAISE) {
        throw new IllegalArgumentException("valuePaise must be 1.." + MAX_PAISE);
      }
      checkMinSpend(minSpendPaise);
    }

    @Override
    public long savingFor(long spendPaise) {
      if (spendPaise < minSpendPaise || spendPaise <= 0) {
        return 0;
      }
      return Math.min(valuePaise, spendPaise);
    }
  }

  private static void checkMinSpend(long minSpendPaise) {
    if (minSpendPaise < 0 || minSpendPaise > MAX_PAISE) {
      throw new IllegalArgumentException("minSpendPaise must be 0.." + MAX_PAISE);
    }
  }
}
