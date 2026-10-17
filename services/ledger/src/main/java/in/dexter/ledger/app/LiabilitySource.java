package in.dexter.ledger.app;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Upcoming liabilities for the calendar (MASTER_PLAN P08: "daily totals + upcoming liabilities projection"): card
 * bill due dates and EMIs belong to portfolio (P11) and planning (P13). Until they publish them, ledger has
 * {@link #NONE} (A-49); the calendar's shape already carries them per day.
 */
public interface LiabilitySource {
  /** {@code kind}: card_bill | emi | subscription; {@code source}: the owning service. */
  record Liability(LocalDate day, String kind, String label, long amountPaise, String source) {}

  List<Liability> between(UUID accountId, LocalDate from, LocalDate toInclusive);

  LiabilitySource NONE = (account, from, to) -> List.of();
}
