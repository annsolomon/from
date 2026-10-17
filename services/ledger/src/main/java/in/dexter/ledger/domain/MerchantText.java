package in.dexter.ledger.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns what a bank statement, UPI app or user typed into the text the resolver matches (MASTER_PLAN P08): the
 * merchant part of UPI/NB/wallet/POS descriptors, without reference numbers, VPA handles, city suffixes and company
 * suffixes, then the same normalisation the seed applies to aliases (lowercase, {@code &} → {@code and}, apostrophes
 * dropped, every other non-alphanumeric run → one space).
 */
public final class MerchantText {
  /** Statement descriptors truncate the compact merchant name at 20 characters (seed + common bank formats). */
  public static final int DESCRIPTOR_TRUNCATION = 20;

  /**
   * The normalised text, its space-free form, whether the descriptor was probably truncated, and the exact-match
   * candidates from most to least specific (the full text first, then with a city / company suffix removed: "air
   * india" must stay "air india", "swiggy bangalore" becomes "swiggy").
   */
  public record Normalized(String text, String compact, boolean maybeTruncated, List<String> variants,
      List<String> compacts) {
    public Normalized {
      variants = List.copyOf(variants);
      compacts = List.copyOf(compacts);
    }

    public boolean isEmpty() {
      return text.isEmpty();
    }
  }

  private static final Pattern UPI = Pattern.compile("(?i)^UPI[/-](?:[A-Z]{2,4}[/-])?\\d{6,}[/-]([^/]+)(?:[/-].*)?$");
  private static final Pattern NB = Pattern.compile("(?i)^(?:NB|NEFT|IMPS|RTGS)[/-]([^/]+?)(?:[/-][0-9A-Z]*\\d[0-9A-Z]*)?$");
  private static final Pattern WALLET = Pattern.compile("(?i)^WALLET\\*(.+)$");
  private static final Pattern POS = Pattern.compile("(?i)^(?:POS|ECOM|ECS)\\s+\\d+\\s+(.+)$");
  private static final Pattern STAR_REF = Pattern.compile("^(.+?)\\*\\d{4,}$");
  private static final Pattern VPA = Pattern.compile("\\S+@[a-z]+", Pattern.CASE_INSENSITIVE);
  private static final Pattern LONG_DIGITS = Pattern.compile("\\b\\d{5,}\\b");
  private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]+");

  /** City suffixes a card descriptor appends (seed: Generator.CITIES, plus a few more); one is dropped. */
  private static final List<String> CITIES = List.of("new delhi", "navi mumbai", "bangalore", "bengaluru", "mumbai",
      "delhi", "chennai", "hyderabad", "pune", "kolkata", "gurgaon", "gurugram", "noida", "ahmedabad", "jaipur",
      "kochi", "coimbatore", "lucknow", "indore", "mysuru", "mysore", "chandigarh", "thane", "surat", "nagpur",
      "bhopal", "visakhapatnam", "trivandrum");
  /** Company forms dropped after the city ("india" stays: "air india", "zara india" are names). */
  private static final List<String> COMPANY = List.of("private limited", "pvt ltd", "pvt", "ltd", "limited", "llp");
  /** Channel words people and banks append ("ajio online", "boat store"); tried last ("croma online" is a name). */
  private static final List<String> CHANNEL = List.of("online", "store", "stores", "shop", "app");

  private MerchantText() {}

  /** The seed's alias normalisation (seed RefData.normalize): the canonical shape of every alias. */
  public static String basic(String s) {
    if (s == null) {
      return "";
    }
    String x = s.toLowerCase(Locale.ROOT).replace("&", " and ").replace("'", "").replace("’", "");
    x = NON_ALNUM.matcher(x).replaceAll(" ");
    return x.strip().replaceAll("\\s+", " ");
  }

  public static Normalized normalize(String raw) {
    if (raw == null) {
      return new Normalized("", "", false, List.of(), List.of());
    }
    String s = raw.strip();
    String core = s;
    boolean descriptor = false;
    Matcher m;
    if ((m = UPI.matcher(s)).matches() || (m = NB.matcher(s)).matches() || (m = WALLET.matcher(s)).matches()
        || (m = POS.matcher(s)).matches() || (m = STAR_REF.matcher(s)).matches()) {
      core = m.group(1);
      descriptor = true;
    }
    core = VPA.matcher(core).replaceAll(" ");
    core = LONG_DIGITS.matcher(core).replaceAll(" ");
    String full = basic(core);
    if (full.isEmpty()) {
      full = basic(s);
    }
    List<String> variants = new ArrayList<>();
    variants.add(full);
    String noCity = strip(full, CITIES);
    addIfNew(variants, noCity);
    String noCompany = strip(noCity, COMPANY);
    addIfNew(variants, noCompany);
    String text = variants.getLast();
    addIfNew(variants, strip(noCompany, CHANNEL));
    List<String> compacts = new ArrayList<>();
    for (String v : variants) {
      addIfNew(compacts, v.replace(" ", ""));
    }
    String compact = text.replace(" ", "");
    // "IRCTCONLINE": a compact descriptor has no word boundary, so the channel word is cut from the end
    for (String c : CHANNEL) {
      if (compact.endsWith(c) && compact.length() - c.length() >= 3) {
        addIfNew(compacts, compact.substring(0, compact.length() - c.length()));
        break;
      }
    }
    boolean truncated = descriptor && !text.contains(" ") && compact.length() == DESCRIPTOR_TRUNCATION;
    return new Normalized(text, compact, truncated, variants, compacts);
  }

  private static void addIfNew(List<String> variants, String v) {
    if (!v.isEmpty() && !variants.contains(v)) {
      variants.add(v);
    }
  }

  /** Drops one trailing suffix of the list (word boundary), keeping at least one word. */
  static String strip(String text, List<String> suffixes) {
    for (String suffix : suffixes) {
      if (text.endsWith(" " + suffix) && text.length() > suffix.length() + 2) {
        return text.substring(0, text.length() - suffix.length() - 1).strip();
      }
    }
    return text;
  }

  /** A user-typed search or autocomplete prefix: {@link #basic} plus a length cap. */
  public static String query(String q) {
    String b = basic(q);
    return b.length() > 60 ? b.substring(0, 60).strip() : b;
  }
}
