package in.dexter.ledger.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * The resolver's labelled evaluation set (MASTER_PLAN P08: 500 raw merchant strings): the seed's reference merchants
 * and aliases ({@code seed/data/v1}) and raw descriptors built the way the seed generator builds them
 * ({@code Generator.rawDescriptor}: UPI, three card shapes, net banking, wallet, cash), each labelled with the
 * merchant it came from. Deterministic (fixed seed).
 */
public final class LabelledMerchants {
  public record Merchant(String slug, String name, String category) {}

  public record Alias(String alias, String slug, int weight) {}

  public record Sample(String raw, String expectedSlug, String method) {}

  static final List<String> CITIES = List.of("BANGALORE", "MUMBAI", "NEW DELHI", "CHENNAI", "HYDERABAD", "PUNE",
      "KOLKATA", "GURGAON", "NOIDA", "AHMEDABAD", "JAIPUR", "KOCHI", "COIMBATORE", "LUCKNOW", "INDORE", "MYSURU");
  static final List<String> HANDLES = List.of("okaxis", "ybl", "oksbi", "okhdfcbank", "okicici", "paytm", "ibl", "axl");
  static final List<String> METHODS = List.of("upi", "card", "card", "card", "netbanking", "wallet", "cash");

  private LabelledMerchants() {}

  public static Path seedData() {
    for (Path p = Path.of("").toAbsolutePath(); p != null; p = p.getParent()) {
      Path d = p.resolve("seed/data/v1");
      if (Files.isDirectory(d)) {
        return d;
      }
    }
    throw new IllegalStateException("seed/data/v1 not found above " + Path.of("").toAbsolutePath());
  }

  public static List<Merchant> merchants() {
    List<Merchant> out = new ArrayList<>();
    for (String[] r : csv(seedData().resolve("merchants.csv"))) {
      out.add(new Merchant(r[0], r[1], r[2]));
    }
    return out;
  }

  public static List<Alias> aliases() {
    List<Alias> out = new ArrayList<>();
    for (String[] r : csv(seedData().resolve("merchant_aliases.csv"))) {
      out.add(new Alias(r[0], r[1], Integer.parseInt(r[2])));
    }
    return out;
  }

  public static List<Sample> samples(int n, long seed) {
    List<Merchant> ms = merchants();
    Random x = new Random(seed);
    List<Sample> out = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      Merchant m = ms.get(x.nextInt(ms.size()));
      String method = METHODS.get(x.nextInt(METHODS.size()));
      out.add(new Sample(raw(m.name(), method, CITIES.get(x.nextInt(CITIES.size())), x), m.slug(), method));
    }
    return out;
  }

  /**
   * The same set with one human/bank-style distortion on the merchant name before the descriptor is built: a
   * dropped letter, two letters swapped, a trailing word ("store", "online", "app"), or a hard cut to 12 characters.
   * Exact alias lookups miss most of these; the trigram stage has to carry them.
   */
  public static List<Sample> noisySamples(int n, long seed) {
    List<Merchant> ms = merchants();
    Random x = new Random(seed);
    List<Sample> out = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      Merchant m = ms.get(x.nextInt(ms.size()));
      String method = METHODS.get(x.nextInt(METHODS.size()));
      String name = distort(m.name(), x);
      out.add(new Sample(raw(name, method, CITIES.get(x.nextInt(CITIES.size())), x), m.slug(), method));
    }
    return out;
  }

  static String distort(String name, Random x) {
    int kind = x.nextInt(4);
    if (name.length() < 6) {
      kind = 2;
    }
    int at = 1 + x.nextInt(Math.max(1, name.length() - 3));
    return switch (kind) {
      case 0 -> name.substring(0, at) + name.substring(at + 1);
      case 1 -> name.substring(0, at) + name.charAt(at + 1) + name.charAt(at) + name.substring(at + 2);
      case 2 -> name + List.of(" Store", " Online", " App").get(x.nextInt(3));
      default -> name.length() > 12 ? name.substring(0, 12) : name + " Store";
    };
  }

  /** Same shapes as the seed's {@code Generator.rawDescriptor}. */
  static String raw(String display, String method, String city, Random x) {
    String upper = display.toUpperCase(Locale.ROOT);
    String compact = upper.replaceAll("[^A-Z0-9]", "");
    if (compact.length() > 20) {
      compact = compact.substring(0, 20);
    }
    return switch (method) {
      case "upi" -> "UPI/" + digits(x, 12) + "/" + compact + "/" + compact.toLowerCase(Locale.ROOT) + digits(x, 2)
          + "@" + HANDLES.get(x.nextInt(HANDLES.size()));
      case "card" -> switch (x.nextInt(3)) {
        case 0 -> upper + " " + city;
        case 1 -> "POS " + digits(x, 6) + " " + upper;
        default -> compact + "*" + digits(x, 8);
      };
      case "netbanking" -> "NB/" + compact + "/" + digits(x, 10);
      case "wallet" -> "WALLET*" + compact;
      default -> display;
    };
  }

  private static String digits(Random x, int n) {
    StringBuilder sb = new StringBuilder(n);
    for (int i = 0; i < n; i++) {
      sb.append((char) ('0' + x.nextInt(10)));
    }
    return sb.toString();
  }

  /** The seed's reference CSVs: header line, comma separated, no quoting (RefData reads them the same way). */
  private static List<String[]> csv(Path file) {
    try {
      List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
      List<String[]> rows = new ArrayList<>();
      for (String l : lines.subList(1, lines.size())) {
        if (!l.isBlank() && !l.startsWith("#")) {
          rows.add(l.split(",", -1));
        }
      }
      return rows;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
