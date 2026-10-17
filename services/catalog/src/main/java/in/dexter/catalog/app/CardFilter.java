package in.dexter.catalog.app;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GET /v1/catalog/cards filters (all optional): network, issuer code, maximum annual fee, and "earns at least
 * {@code minRateBps} on {@code category}" (the most specific matching rule of the current version).
 */
public record CardFilter(String network, String issuer, Long maxAnnualFee, String category, Integer minRateBps) {
  public static final CardFilter NONE = new CardFilter(null, null, null, null, null);

  /** What the cursor is bound to. */
  public Map<String, String> asMap() {
    Map<String, String> m = new LinkedHashMap<>();
    put(m, "network", network);
    put(m, "issuer", issuer);
    put(m, "maxAnnualFee", maxAnnualFee);
    put(m, "category", category);
    put(m, "minRateBps", minRateBps);
    return m;
  }

  private static void put(Map<String, String> m, String k, Object v) {
    if (v != null) {
      m.put(k, v.toString());
    }
  }
}
