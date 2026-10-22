package in.dexter.portfolio.support;

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
 * The seed's reference merchants ({@code seed/data/v1/merchants.csv}) and card descriptors built the way the seed
 * generator builds them ({@code Generator.rawDescriptor}, card shapes: "NAME CITY", "POS 123456 NAME",
 * "COMPACT*12345678"). The test reads the CSV directly (the seed jar is repackaged, A-49).
 */
public final class SeedMerchants {
  static final List<String> CITIES = List.of("BANGALORE", "MUMBAI", "DELHI", "PUNE", "CHENNAI", "HYDERABAD",
      "KOLKATA", "GURGAON", "NOIDA", "AHMEDABAD");

  private SeedMerchants() {}

  static Path seedData() {
    for (Path p = Path.of("").toAbsolutePath(); p != null; p = p.getParent()) {
      Path d = p.resolve("seed/data/v1");
      if (Files.isDirectory(d)) {
        return d;
      }
    }
    throw new IllegalStateException("seed/data/v1 not found above " + Path.of("").toAbsolutePath());
  }

  /** Canonical merchant names. */
  public static List<String> names() {
    try {
      List<String> lines = Files.readAllLines(seedData().resolve("merchants.csv"), StandardCharsets.UTF_8);
      List<String> out = new ArrayList<>();
      for (String l : lines.subList(1, lines.size())) {
        if (!l.isBlank() && !l.startsWith("#")) {
          out.add(l.split(",", -1)[1]);
        }
      }
      return out;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** A card descriptor for the merchant, in one of the seed's three card shapes. */
  public static String cardDescriptor(String display, Random x) {
    String upper = display.toUpperCase(Locale.ROOT);
    String compact = upper.replaceAll("[^A-Z0-9]", "");
    if (compact.length() > 20) {
      compact = compact.substring(0, 20);
    }
    return switch (x.nextInt(3)) {
      case 0 -> upper + " " + CITIES.get(x.nextInt(CITIES.size()));
      case 1 -> "POS " + digits(x, 6) + " " + upper;
      default -> compact + "*" + digits(x, 8);
    };
  }

  static String digits(Random x, int n) {
    StringBuilder sb = new StringBuilder(n);
    for (int i = 0; i < n; i++) {
      sb.append((char) ('0' + x.nextInt(10)));
    }
    return sb.toString();
  }
}
