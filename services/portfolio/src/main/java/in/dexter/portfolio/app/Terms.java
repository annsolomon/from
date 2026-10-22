package in.dexter.portfolio.app;

import in.dexter.portfolio.domain.RewardTerms;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Reward terms out of a catalog snapshot (the projection's JSON, catalog's published shape). */
final class Terms {
  private Terms() {}

  static RewardTerms of(CardStore.Product p, ObjectMapper mapper) {
    if (p == null) {
      return new RewardTerms(List.of());
    }
    JsonNode snap = mapper.readTree(p.snapshotJson());
    List<RewardTerms.Rule> rules = new ArrayList<>();
    JsonNode rs = snap.get("rules");
    if (rs != null && rs.isArray()) {
      for (JsonNode r : rs) {
        List<String> excluded = new ArrayList<>();
        JsonNode ex = r.get("excludedPaths");
        if (ex != null && ex.isArray()) {
          ex.forEach(x -> excluded.add(x.asString()));
        }
        rules.add(new RewardTerms.Rule(text(r, "categoryPath"), uuid(r, "merchantId"),
            text(r, "rewardType") == null ? "cashback" : text(r, "rewardType"), num(r, "rateBps"),
            num(r, "pointValueMilliPaise"), num(r, "minTxnPaise"), excluded, num(r, "priority")));
      }
    }
    return new RewardTerms(rules);
  }

  /** The card's point value (milli-paise per point) from its points/miles rules, 0 when it earns cashback. */
  static long pointValue(RewardTerms t) {
    for (RewardTerms.Rule r : t.rules()) {
      if (!"cashback".equals(r.rewardType()) && r.pointValueMilliPaise() > 0) {
        return r.pointValueMilliPaise();
      }
    }
    return 0;
  }

  private static String text(JsonNode n, String f) {
    JsonNode v = n.get(f);
    return v == null || v.isNull() ? null : v.asString();
  }

  private static UUID uuid(JsonNode n, String f) {
    String s = text(n, f);
    try {
      return s == null ? null : UUID.fromString(s);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static int num(JsonNode n, String f) {
    JsonNode v = n.get(f);
    return v == null || v.isNull() || !v.isNumber() ? 0 : (int) Math.min(Integer.MAX_VALUE, v.asLong());
  }
}
