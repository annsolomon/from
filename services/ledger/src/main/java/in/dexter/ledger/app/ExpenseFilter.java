package in.dexter.ledger.app;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * GET /v1/expenses filters (all optional): local dates {@code from}/{@code to} inclusive (Asia/Kolkata), a category
 * subtree, payment method, card, merchant, amount range. {@link #asMap} is what the cursor is bound to.
 */
public record ExpenseFilter(LocalDate from, LocalDate to, String category, String paymentMethod, UUID cardId,
    UUID merchantId, Long minPaise, Long maxPaise) {

  public static final ExpenseFilter NONE = new ExpenseFilter(null, null, null, null, null, null, null, null);

  public Map<String, String> asMap() {
    Map<String, String> m = new LinkedHashMap<>();
    put(m, "from", from);
    put(m, "to", to);
    put(m, "category", category);
    put(m, "paymentMethod", paymentMethod);
    put(m, "cardId", cardId);
    put(m, "merchantId", merchantId);
    put(m, "minPaise", minPaise);
    put(m, "maxPaise", maxPaise);
    return m;
  }

  private static void put(Map<String, String> m, String k, Object v) {
    if (v != null) {
      m.put(k, v.toString());
    }
  }
}
