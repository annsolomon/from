package in.dexter.portfolio.domain;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Auto-match scoring (MASTER_PLAN P11): a card transaction and a ledger expense are the same purchase when they are
 * on the same card, for the same amount, within ±2 days, and their merchants agree. Merchant agreement is 1 when both
 * carry the same resolved merchant id, or when the expense's merchant name, compacted, occurs in the bank descriptor
 * starting at a word ("Swiggy" in "SWIGGY*ORDER 8812 BANGALORE", "H&M India" in "POS 452392 H&M INDIA", but not "LIC"
 * in "DELHI PUBLIC SCHOOL"), or when a long descriptor is the start of the name (descriptors cut names at 20
 * characters). Otherwise it is the trigram similarity (pg_trgm's measure) of the name against the descriptor, light
 * (legal suffixes dropped) or strict (cities, gateways and "india" dropped from the descriptor), whichever is higher
 * — that tolerates typos. Containment is one-way on purpose: a short descriptor word must not match a longer name
 * that merely contains it ("KOLKATA METRO" vs "Hyderabad Metro"). The score
 * weighs merchant agreement 0.75 and date distance 0.25. Pure.
 */
public final class MatchScore {
  public static final int MAX_DAYS = 2;
  /** Below this merchant agreement nothing is suggested, however close amount and date are. */
  public static final double MIN_MERCHANT = 0.45;
  /** A descriptor this long that starts the merchant name is a truncated name. */
  static final int TRUNCATED_MIN = 10;
  static final Set<String> LEGAL = Set.of("pvt", "ltd", "private", "limited", "llp", "inc", "the", "and", "www",
      "com", "co");
  static final Set<String> NOISE = Set.of("pvt", "ltd", "private", "limited", "llp", "inc", "india", "in", "ind",
      "www", "com", "co", "the", "and", "payments", "payment", "pay", "pos", "ecom", "online", "upi", "razorpay",
      "payu", "ccavenue", "billdesk", "paytm", "store", "stores", "retail", "services", "service", "technologies",
      "bangalore", "bengaluru", "mumbai", "delhi", "new", "gurgaon", "gurugram", "noida", "pune", "chennai",
      "hyderabad", "kolkata", "ahmedabad", "jaipur", "kochi", "order", "orders", "purchase", "txn", "ref");

  private MatchScore() {}

  /** Lower-case alphanumeric words without noise words, digits-only tokens and single letters. */
  public static String normalize(String s) {
    return words(s, NOISE, 2);
  }

  /** Lower-case alphanumeric words without legal suffixes and digits-only tokens. */
  public static String light(String s) {
    return words(s, LEGAL, 1);
  }

  static String words(String s, Set<String> drop, int minLength) {
    if (s == null) {
      return "";
    }
    StringBuilder out = new StringBuilder();
    for (String w : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim().split(" ")) {
      if (w.length() < minLength || drop.contains(w) || w.chars().allMatch(Character::isDigit)) {
        continue;
      }
      if (!out.isEmpty()) {
        out.append(' ');
      }
      out.append(w);
    }
    return out.toString();
  }

  static String compact(String normalized) {
    return normalized.replace(" ", "");
  }

  /** Merchant agreement in [0, 1]. */
  public static double merchant(String expenseMerchant, UUID expenseMerchantId, String txnDescription,
      UUID txnMerchantId) {
    if (expenseMerchantId != null && expenseMerchantId.equals(txnMerchantId)) {
      return 1.0;
    }
    String e = compact(light(expenseMerchant));
    String dWords = light(txnDescription);
    String d = compact(dWords);
    if (e.isEmpty() || d.isEmpty()) {
      return 0.0;
    }
    if (e.length() >= 3 && containsAtWordStart(dWords, e) || d.length() >= TRUNCATED_MIN && e.startsWith(d)) {
      return 1.0;
    }
    double best = trigram(e, d);
    String sd = compact(normalize(txnDescription));
    if (!sd.isEmpty()) {
      best = Math.max(best, trigram(e, sd));
    }
    return best;
  }

  /** True when the compacted {@code needle} occurs in the compacted words starting at the start of a word. */
  static boolean containsAtWordStart(String words, String needle) {
    String hay = compact(words);
    int offset = 0;
    for (String w : words.split(" ")) {
      if (hay.startsWith(needle, offset)) {
        return true;
      }
      offset += w.length();
    }
    return false;
  }

  /** pg_trgm-style similarity: |common trigrams| / |all trigrams| of the space-padded strings. */
  static double trigram(String a, String b) {
    Set<String> ta = trigrams(a);
    Set<String> tb = trigrams(b);
    if (ta.isEmpty() || tb.isEmpty()) {
      return 0.0;
    }
    Set<String> common = new HashSet<>(ta);
    common.retainAll(tb);
    Set<String> all = new HashSet<>(ta);
    all.addAll(tb);
    return (double) common.size() / all.size();
  }

  static Set<String> trigrams(String s) {
    Set<String> out = new HashSet<>();
    String p = "  " + s + " ";
    for (int i = 0; i + 3 <= p.length(); i++) {
      out.add(p.substring(i, i + 3));
    }
    return out;
  }

  /** The overall score, or -1 when the pair must not be suggested (days or merchant out of bounds). */
  public static double score(double merchant, long daysApart) {
    long d = Math.abs(daysApart);
    if (d > MAX_DAYS || merchant < MIN_MERCHANT) {
      return -1;
    }
    double date = 1.0 - d / (double) (MAX_DAYS + 1);
    return Math.round((0.75 * merchant + 0.25 * date) * 1000) / 1000.0;
  }
}
