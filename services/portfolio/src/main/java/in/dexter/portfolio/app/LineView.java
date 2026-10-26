package in.dexter.portfolio.app;

import in.dexter.portfolio.app.StatementStore.LineRow;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One parsed statement line (P12): {@code status} pending (will import), duplicate (a card transaction already has
 * it), excluded (left out on confirm) or imported ({@code cardTxnId} = the transaction it became).
 */
public record LineView(int lineNo, LocalDate postedOn, String description, long amountPaise, String direction,
    String kind, String status, UUID cardTxnId) {

  static LineView of(LineRow l) {
    return new LineView(l.lineNo(), l.postedOn(), l.description(), l.amountPaise(), l.direction(), l.kind(),
        l.status(), l.cardTxnId());
  }
}
