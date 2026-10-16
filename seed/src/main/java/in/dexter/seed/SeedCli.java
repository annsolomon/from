package in.dexter.seed;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@code java -jar seed/target/seed.jar <command> [options]} (driven by {@code scripts/seed.sh}):
 *
 * <pre>
 *   generate --size small|medium|large|custom:A,E,C [--seed 42] [--out DIR] [--fixtures DIR]
 *   load     --dir DIR [--replace] [--host 127.0.0.1] [--port 5432]
 *   checksum --dir DIR          verifies every file against the manifest, prints the dataset checksum
 * </pre>
 *
 * Passwords come from the environment ({@code .env}): {@code DEMO_PASSWORD_ASHA|RAVI|ADMIN},
 * {@code SEED_BULK_PASSWORD} for generate; {@code PG_PW_<SVC>_OWNER} or {@code PG_PW_OWNER} for load.
 */
public final class SeedCli {
  private final PrintStream out;
  private final PrintStream err;
  private final Map<String, String> env;

  SeedCli(PrintStream out, PrintStream err, Map<String, String> env) {
    this.out = out;
    this.err = err;
    this.env = env;
  }

  public static void main(String[] args) {
    // pgjdbc sends the JVM default zone as TimeZone at connect; some hosts report legacy aliases (Asia/Calcutta)
    // that a server may reject. Nothing in the seed depends on the default zone (all times are explicit).
    java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
    int code = new SeedCli(System.out, System.err, System.getenv()).run(args);
    System.exit(code);
  }

  int run(String[] args) {
    if (args.length == 0) {
      return usage("no command");
    }
    Map<String, String> opt;
    try {
      opt = options(args);
    } catch (IllegalArgumentException e) {
      return usage(e.getMessage());
    }
    try {
      return switch (args[0]) {
        case "generate" -> generate(opt);
        case "load" -> load(opt);
        case "checksum" -> checksum(opt);
        default -> usage("unknown command " + args[0]);
      };
    } catch (RuntimeException e) {
      err.println("seed " + args[0] + " FAILED: " + e.getMessage());
      return 1;
    }
  }

  private int generate(Map<String, String> opt) {
    Size size = Size.parse(require(opt, "size"));
    long seed = Long.parseLong(opt.getOrDefault("seed", "42"));
    Path outDir = Path.of(opt.getOrDefault("out", "target/seed/" + size.name().replace(':', '-') + "-" + seed));
    Path fixtures = opt.containsKey("fixtures") ? Path.of(opt.get("fixtures")) : null;
    Map<String, String> pw = new HashMap<>();
    for (String k : List.of(Persona.ASHA.passwordEnv(), Persona.RAVI.passwordEnv(), Persona.ADMIN.passwordEnv(),
        Persona.BULK_PASSWORD_ENV)) {
      String v = env.get(k);
      if (v == null || v.isEmpty()) {
        throw new IllegalArgumentException(k + " is not set (source .env first: scripts/seed.sh does this)");
      }
      pw.put(k, v);
    }
    out.printf(Locale.ROOT, "seed generate: size=%s (%d accounts, %d expenses, %d user cards) seed=%d data=%s -> %s%n",
        size.name(), size.accounts(), size.expenses(), size.userCards(), seed, RefData.VERSION, outDir);
    Generator.Result r = new Generator(new Generator.Config(seed, size, outDir, fixtures, pw)).run();
    new TreeMap<>(r.rows()).forEach((t, n) -> out.printf(Locale.ROOT, "  %-34s %10d rows%n", t, n));
    out.printf(Locale.ROOT, "generated in %.1f s%n", r.elapsed().toMillis() / 1000.0);
    out.println("checksum " + r.checksum());
    return 0;
  }

  private int load(Map<String, String> opt) {
    Path dir = Path.of(require(opt, "dir"));
    String host = opt.getOrDefault("host", env.getOrDefault("DEXTER_PG_HOST", "127.0.0.1"));
    String port = opt.getOrDefault("port", env.getOrDefault("POSTGRES_PORT", "5432"));
    boolean replace = opt.containsKey("replace");
    long t0 = System.nanoTime();
    out.println("seed load: " + dir + " -> postgres " + host + ":" + port + " as <svc>_owner"
        + (replace ? " (replace)" : ""));
    List<Loader.Loaded> loaded = new Loader(out::println).load(dir, svc -> {
      String pw = env.get("PG_PW_" + svc.toUpperCase(Locale.ROOT) + "_OWNER");
      if (pw == null || pw.isEmpty()) {
        pw = env.get("PG_PW_OWNER");
      }
      if (pw == null || pw.isEmpty()) {
        throw new IllegalArgumentException("PG_PW_OWNER is not set (source .env first)");
      }
      return new Loader.Target("jdbc:postgresql://" + host + ":" + port + "/" + svc, svc + "_owner", pw);
    }, replace);
    long rows = loaded.stream().mapToLong(Loader.Loaded::rows).sum();
    out.printf(Locale.ROOT, "loaded %d tables, %d rows in %.1f s%n", loaded.size(), rows,
        (System.nanoTime() - t0) / 1e9);
    return 0;
  }

  private int checksum(Map<String, String> opt) {
    Path dir = Path.of(require(opt, "dir"));
    List<Loader.Entry> entries = Loader.readManifest(dir);
    Loader.verifyFiles(dir, entries);
    String json;
    try {
      json = Files.readString(dir.resolve("manifest.json"));
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
    java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"checksum\": \"([0-9a-f]{64})\"").matcher(json);
    if (!m.find()) {
      throw new IllegalStateException("manifest.json has no checksum");
    }
    out.println("files verified: " + entries.size());
    out.println("checksum " + m.group(1));
    return 0;
  }

  private static Map<String, String> options(String[] args) {
    Map<String, String> opt = new HashMap<>();
    for (int i = 1; i < args.length; i++) {
      String a = args[i];
      if (!a.startsWith("--")) {
        throw new IllegalArgumentException("unexpected argument " + a);
      }
      String k = a.substring(2);
      if (k.equals("replace")) {
        opt.put(k, "true");
      } else {
        if (i + 1 >= args.length) {
          throw new IllegalArgumentException("--" + k + " needs a value");
        }
        opt.put(k, args[++i]);
      }
    }
    return opt;
  }

  private static String require(Map<String, String> opt, String k) {
    String v = opt.get(k);
    if (v == null) {
      throw new IllegalArgumentException("--" + k + " is required");
    }
    return v;
  }

  private int usage(String why) {
    err.println("seed: " + why);
    err.println("usage: generate --size small|medium|large|custom:A,E,C [--seed 42] [--out DIR] [--fixtures DIR]");
    err.println("       load --dir DIR [--replace] [--host 127.0.0.1] [--port 5432]");
    err.println("       checksum --dir DIR");
    return 2;
  }
}
