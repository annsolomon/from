package in.dexter.catalog.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extractor {@value #VERSION}: card facts out of an issuer's product page by text patterns (Indian issuer wording:
 * rupee amounts with lakh, "per month" interest, "X% cashback on ..."). It reads fees, the fee waiver spend, forex
 * markup, interest (APR), the fuel surcharge waiver and cashback rates per category; it never invents a field it did
 * not find. Each field carries a confidence: 1.0 for one unambiguous statement, 0.5 when the page states several
 * different values (the first wins), 0.8 for a value implied by wording ("lifetime free"). The extraction's
 * confidence is the lowest of its fields. Pure.
 */
public final class CardExtractor {
  public static final String VERSION = "pattern-v1";
  /** Fields the extractor can fill, in document order. */
  public static final List<String> FIELDS = List.of("joiningFeePaise", "annualFeePaise", "feeWaiverSpendPaise",
      "aprBps", "forexMarkupBps", "fuelSurchargeWaiverBps");

  /** One extracted value (Long paise or Integer bps). */
  public record Field(Object value, double confidence) {}

  /** A cashback rate; {@code categoryPath} null = the base rate ("all other spends"). */
  public record RuleHit(String categoryPath, int rateBps, double confidence, String phrase) {}

  public record Extraction(String name, Map<String, Field> fields, List<RuleHit> rules, List<String> unmapped,
      double confidence) {
    public boolean isEmpty() {
      return fields.isEmpty() && rules.isEmpty();
    }
  }

  static final String AMOUNT = "₹\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*(lakhs?|lacs?|crores?|k)?\\b";
  static final String SEP = "\\s*(?::|-|–|—|is|of|at|=)?\\s*";
  private static final Pattern ANNUAL = Pattern.compile("(?:annual|renewal|yearly)\\s+(?:membership\\s+)?fees?"
      + "(?:\\s*\\([^)]{0,40}\\))?" + SEP + "(?:" + AMOUNT + "|(nil|free|zero))");
  private static final Pattern JOINING = Pattern.compile("(?:joining|first[- ]year|one[- ]time)\\s+(?:membership\\s+)?"
      + "fees?(?:\\s*\\([^)]{0,40}\\))?" + SEP + "(?:" + AMOUNT + "|(nil|free|zero))");
  private static final Pattern WAIVER = Pattern.compile("(?:annual|renewal)\\s+fees?\\s+(?:is\\s+|will\\s+be\\s+|gets\\s+)?"
      + "(?:waived|reversed)(?:\\s+off)?\\s+(?:on|if|when|for|upon)\\s+(?:annual\\s+|yearly\\s+|total\\s+|a\\s+)?"
      + "(?:spends?|spending|you\\s+spend|purchases|retail\\s+spends?)\\s+(?:of\\s+|above\\s+|over\\s+|exceeding\\s+|"
      + "more\\s+than\\s+|worth\\s+|of\\s+at\\s+least\\s+)?" + AMOUNT);
  private static final Pattern WAIVER2 = Pattern.compile("spend\\s+(?:of\\s+)?" + AMOUNT + "\\s+(?:or\\s+more\\s+)?"
      + "(?:in\\s+a\\s+(?:card\\s+)?year\\s+|annually\\s+)?(?:to|and)\\s+(?:get\\s+)?(?:the\\s+|your\\s+)?"
      + "(?:annual|renewal)\\s+fee\\s+(?:waived|reversed)");
  private static final Pattern FOREX = Pattern.compile("(?:foreign\\s+currency|forex|cross[- ]currency|international)"
      + "\\s+(?:transaction\\s+)?(?:mark[- ]?up|fee|charges?)(?:\\s+fee)?" + SEP + "(\\d+(?:\\.\\d+)?)\\s*%");
  private static final Pattern APR_MONTH = Pattern.compile("(?:interest|finance\\s+charges?|revolving\\s+credit)"
      + "[^\\n%]{0,60}?(\\d+(?:\\.\\d+)?)\\s*%\\s*(?:per\\s+month|p\\.?\\s?m\\.?(?![a-z])|a\\s+month|monthly)");
  private static final Pattern APR_YEAR = Pattern.compile("(?:interest|finance\\s+charges?|apr)"
      + "[^\\n%]{0,60}?(\\d+(?:\\.\\d+)?)\\s*%\\s*(?:per\\s+annum|p\\.?\\s?a\\.?(?![a-z])|annually|a\\s+year|apr)");
  private static final Pattern FUEL = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*%\\s*fuel\\s+surcharge\\s+waiver");
  private static final Pattern LIFETIME_FREE = Pattern.compile("\\blifetime\\s+free\\b");
  private static final Pattern CASHBACK = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*%\\s*(?:unlimited\\s+)?"
      + "(?:cashback|cash\\s+back|value[- ]?back)\\s+on\\s+([^\\n.;:()]{2,80})");
  private static final Pattern RUPEE_WORDS = Pattern.compile("(?<![a-z])(?:rs\\.?|inr)\\s*(?=[0-9])");

  /** Phrase keywords → category path (null = base rate). Longer keywords first at the same position. */
  static final Map<String, String> CATEGORY_WORDS = categoryWords();

  private static Map<String, String> categoryWords() {
    Map<String, String> m = new LinkedHashMap<>();
    for (String k : List.of("all other spends", "all other purchases", "all other transactions", "all other",
        "other spends", "other purchases", "all spends", "all retail spends", "all retail purchases",
        "everything else", "every spend", "all transactions", "other categories")) {
      m.put(k, null);
    }
    put(m, "food.delivery", "food delivery", "swiggy", "zomato", "food ordering");
    put(m, "food.dining", "dining", "restaurants", "restaurant");
    put(m, "food.groceries", "groceries", "grocery", "supermarkets", "supermarket", "bigbasket", "blinkit");
    put(m, "food.cafe", "cafes", "cafe", "coffee");
    put(m, "travel.air", "flights", "flight", "air tickets", "airlines", "airline");
    put(m, "travel.hotel", "hotels", "hotel");
    put(m, "travel.cab", "cabs", "cab rides", "taxi", "uber", "ola");
    put(m, "travel.rail", "rail", "train", "irctc");
    put(m, "travel.bus", "bus tickets", "bus");
    put(m, "travel", "travel");
    put(m, "fuel", "fuel", "petrol", "diesel");
    put(m, "shopping.online", "online shopping", "online spends", "online spend", "online purchases", "e-commerce",
        "ecommerce", "amazon", "flipkart", "myntra");
    put(m, "shopping.fashion", "fashion", "apparel", "clothing");
    put(m, "shopping.electronics", "electronics");
    put(m, "shopping", "shopping");
    put(m, "utilities.electricity", "electricity");
    put(m, "utilities.mobile", "mobile recharges", "mobile recharge", "recharges", "postpaid");
    put(m, "utilities.broadband", "broadband", "internet");
    put(m, "utilities.dth", "dth");
    put(m, "utilities", "utility bills", "utilities", "bill payments", "bills");
    put(m, "entertainment.movies", "movie tickets", "movies", "bookmyshow");
    put(m, "entertainment.ott", "streaming", "ott");
    put(m, "entertainment", "entertainment");
    put(m, "health.pharmacy", "pharmacy", "medicines");
    put(m, "health", "health", "healthcare");
    put(m, "education", "education", "school fees", "college fees", "tuition");
    put(m, "finance.insurance", "insurance");
    put(m, "housing.rent", "rent");
    return m;
  }

  private static void put(Map<String, String> m, String path, String... words) {
    for (String w : words) {
      m.put(w, path);
    }
  }

  private CardExtractor() {}

  public static Extraction extract(String html) {
    String name = HtmlText.title(html);
    String text = normalize(HtmlText.text(html));
    Map<String, Field> fields = new LinkedHashMap<>();
    fee(fields, "joiningFeePaise", JOINING, text);
    fee(fields, "annualFeePaise", ANNUAL, text);
    if (LIFETIME_FREE.matcher(text).find()) {
      fields.putIfAbsent("joiningFeePaise", new Field(0L, 0.8));
      fields.putIfAbsent("annualFeePaise", new Field(0L, 0.8));
    }
    List<Long> waivers = amounts(WAIVER, text, 1);
    waivers.addAll(amounts(WAIVER2, text, 1));
    field(fields, "feeWaiverSpendPaise", waivers);
    field(fields, "forexMarkupBps", percents(FOREX, text, 100));
    List<Long> apr = percents(APR_MONTH, text, 1200);
    if (apr.isEmpty()) {
      apr = percents(APR_YEAR, text, 100);
    }
    field(fields, "aprBps", apr);
    field(fields, "fuelSurchargeWaiverBps", percents(FUEL, text, 100));
    // keep FIELDS order
    Map<String, Field> ordered = new LinkedHashMap<>();
    for (String f : FIELDS) {
      if (fields.containsKey(f)) {
        ordered.put(f, fields.get(f));
      }
    }

    Map<String, List<Integer>> rates = new LinkedHashMap<>();
    Map<String, String> phrases = new LinkedHashMap<>();
    List<String> unmapped = new ArrayList<>();
    Matcher m = CASHBACK.matcher(text);
    while (m.find()) {
      Integer bps = bps(m.group(1), 100);
      String phrase = m.group(2).strip();
      if (bps == null || bps <= 0 || bps > 2500) {
        continue;
      }
      String key = category(phrase);
      if (key == null) {
        if (unmapped.size() < 20) {
          unmapped.add(phrase);
        }
        continue;
      }
      rates.computeIfAbsent(key, k -> new ArrayList<>()).add(bps);
      phrases.putIfAbsent(key, phrase);
    }
    List<RuleHit> rules = new ArrayList<>();
    rates.forEach((key, list) -> rules.add(new RuleHit(key.equals(BASE) ? null : key, list.getFirst(),
        new LinkedHashSet<>(list).size() == 1 ? 1.0 : 0.5, phrases.get(key))));

    double confidence = 1.0;
    boolean any = false;
    for (Field f : ordered.values()) {
      confidence = Math.min(confidence, f.confidence());
      any = true;
    }
    for (RuleHit r : rules) {
      confidence = Math.min(confidence, r.confidence());
      any = true;
    }
    return new Extraction(name, ordered, rules, unmapped, any ? confidence : 0.0);
  }

  static final String BASE = "<base>";

  /** The category of a phrase: the keyword found earliest (longest at the same position); BASE or null. */
  static String category(String phrase) {
    String p = " " + phrase.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9& -]", " ").replaceAll("\\s+", " ") + " ";
    int bestAt = Integer.MAX_VALUE;
    int bestLen = 0;
    String best = null;
    boolean found = false;
    for (Map.Entry<String, String> e : CATEGORY_WORDS.entrySet()) {
      int at = p.indexOf(" " + e.getKey() + " ");
      if (at >= 0 && (at < bestAt || at == bestAt && e.getKey().length() > bestLen)) {
        bestAt = at;
        bestLen = e.getKey().length();
        best = e.getValue();
        found = true;
      }
    }
    return !found ? null : best == null ? BASE : best;
  }

  static String normalize(String text) {
    String t = text.toLowerCase(Locale.ROOT).replace('₹', '₹');
    return RUPEE_WORDS.matcher(t).replaceAll("₹");
  }

  private static void fee(Map<String, Field> fields, String name, Pattern p, String text) {
    List<Long> values = new ArrayList<>();
    Matcher m = p.matcher(text);
    while (m.find()) {
      if (m.group(3) != null) {
        values.add(0L);
      } else {
        Long v = paise(m.group(1), m.group(2));
        if (v != null) {
          values.add(v);
        }
      }
    }
    field(fields, name, values);
  }

  private static void field(Map<String, Field> fields, String name, List<? extends Number> values) {
    if (values.isEmpty()) {
      return;
    }
    Number first = values.getFirst();
    Object value = name.endsWith("Bps") ? (Object) first.intValue() : (Object) first.longValue();
    fields.put(name, new Field(value, new LinkedHashSet<>(values.stream().map(Number::longValue).toList()).size() == 1
        ? 1.0 : 0.5));
  }

  private static List<Long> amounts(Pattern p, String text, int group) {
    List<Long> out = new ArrayList<>();
    Matcher m = p.matcher(text);
    while (m.find()) {
      Long v = paise(m.group(group), m.group(group + 1));
      if (v != null) {
        out.add(v);
      }
    }
    return out;
  }

  private static List<Long> percents(Pattern p, String text, int factor) {
    List<Long> out = new ArrayList<>();
    Matcher m = p.matcher(text);
    while (m.find()) {
      Integer v = bps(m.group(1), factor);
      if (v != null && v >= 0 && v <= 10_000) {
        out.add(v.longValue());
      }
    }
    return out;
  }

  /** "1,00,000" / "1.5" + "lakh" → paise (exact decimal arithmetic). */
  static Long paise(String number, String unit) {
    try {
      BigDecimal n = new BigDecimal(number.replace(",", ""));
      if (unit != null) {
        String u = unit.toLowerCase(Locale.ROOT);
        n = n.multiply(u.startsWith("la") ? BigDecimal.valueOf(100_000) : u.startsWith("cr")
            ? BigDecimal.valueOf(10_000_000) : BigDecimal.valueOf(1_000));
      }
      BigDecimal p = n.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP);
      return p.signum() < 0 || p.compareTo(BigDecimal.valueOf(1_000_000_000_000L)) > 0 ? null : p.longValueExact();
    } catch (ArithmeticException | NumberFormatException e) {
      return null;
    }
  }

  /** "3.5" % with factor 1200 (per month → bps a year) or 100 → bps. */
  static Integer bps(String percent, int factor) {
    try {
      return new BigDecimal(percent).multiply(BigDecimal.valueOf(factor)).setScale(0, RoundingMode.HALF_UP)
          .intValueExact();
    } catch (ArithmeticException | NumberFormatException e) {
      return null;
    }
  }

  /** The category keywords as a set (tests). */
  static Set<String> keywords() {
    return CATEGORY_WORDS.keySet();
  }
}
