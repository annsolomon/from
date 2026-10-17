package in.dexter.catalog.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Card documents and issuers for the catalog ITs (unique slugs/codes per call). */
public final class Cards {
  private Cards() {}

  public static String suffix() {
    return UUID.randomUUID().toString().substring(0, 8);
  }

  public static Map<String, Object> issuer(String code) {
    return Map.of("code", code, "name", "Test Issuer " + code, "type", "bank");
  }

  public static Map<String, Object> rule(String path, int rateBps) {
    Map<String, Object> r = new LinkedHashMap<>();
    r.put("categoryPath", path);
    r.put("rewardType", "cashback");
    r.put("rateBps", rateBps);
    return r;
  }

  /** A card with a 1 % base rate excluding fuel, 5 % on food.delivery (capped) and a milestone. */
  public static Map<String, Object> doc(String slug, String name, String issuerCode, String network, long annualFee) {
    Map<String, Object> d = new LinkedHashMap<>();
    d.put("slug", slug);
    d.put("name", name);
    d.put("issuerCode", issuerCode);
    d.put("network", network);
    d.put("tier", "entry");
    d.put("joiningFeePaise", annualFee);
    d.put("annualFeePaise", annualFee);
    d.put("feeWaiverSpendPaise", 10_000_000);
    d.put("aprBps", 4200);
    d.put("forexMarkupBps", 350);
    d.put("fuelSurchargeWaiverBps", 100);
    d.put("features", Map.of("lounge_domestic", 2, "contactless", true));
    List<Map<String, Object>> rules = new ArrayList<>();
    Map<String, Object> base = rule(null, 100);
    base.put("excludedPaths", List.of("fuel"));
    rules.add(base);
    Map<String, Object> food = rule("food.delivery", 500);
    food.put("capPaise", 100_000);
    food.put("capPeriod", "statement");
    food.put("priority", 110);
    rules.add(food);
    d.put("rules", rules);
    d.put("milestone", Map.of("spendThresholdPaise", 10_000_000, "period", "year", "benefitType", "voucher",
        "benefitValuePaise", 100_000));
    return d;
  }
}
