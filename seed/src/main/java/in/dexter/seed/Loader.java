package in.dexter.seed;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.postgresql.PGConnection;

/**
 * Loads a generated dataset ({@code manifest.tsv} + COPY files) into the service databases as each service's
 * {@code <svc>_owner} role (ADR-0008):
 *
 * <ul>
 *   <li><b>Real table exists</b> (its phase's Flyway migration ran): one transaction per table that checks every
 *       seeded column exists, turns {@code FORCE ROW LEVEL SECURITY} off, empties the table ({@code --replace}
 *       only), COPYs, counts, turns FORCE back on and commits. DDL is transactional, so nobody ever sees the table
 *       unforced, and a failure rolls everything back. Without FORCE the owner is exempt from RLS, which is the
 *       only reason the owner can bulk-load tenant rows at all.
 *   <li><b>Not created yet</b> (P03..the phase that creates it): the same rows go into
 *       {@code seed_stage.<table>}, a schema only the owner can reach, built from the planned column list. The
 *       medium/large timing and row counts are real; the switch to the real table is automatic.
 * </ul>
 */
public final class Loader {
  private static final Pattern IDENT = Pattern.compile("[a-z_][a-z0-9_]*");

  /** Where and as whom to connect for one service database. */
  public record Target(String url, String user, String password) {}

  @FunctionalInterface
  public interface Targets {
    Target forService(String service);
  }

  public record Entry(String service, String table, String phase, String file, long rows, String sha256,
      List<String> columns) {}

  public record Loaded(String service, String table, String into, long rows, long millis) {}

  private final PrintSink log;

  public Loader(PrintSink log) {
    this.log = log;
  }

  /** Progress output (the CLI prints, tests collect). */
  @FunctionalInterface
  public interface PrintSink {
    void line(String s);
  }

  public static List<Entry> readManifest(Path dir) {
    List<Entry> out = new ArrayList<>();
    try {
      for (String line : Files.readAllLines(dir.resolve("manifest.tsv"), StandardCharsets.UTF_8)) {
        if (line.isBlank() || line.startsWith("#")) {
          continue;
        }
        String[] f = line.split("\t", -1);
        if (f.length != 7) {
          throw new IllegalStateException("manifest.tsv: bad line: " + line);
        }
        Entry e = new Entry(f[0], f[1], f[2], f[3], Long.parseLong(f[4]), f[5], List.of(f[6].split(",")));
        ident(e.service());
        ident(e.table());
        e.columns().forEach(Loader::ident);
        if (!e.file().equals(e.service() + "/" + e.table() + ".tsv")) {
          throw new IllegalStateException("manifest.tsv: unexpected file path " + e.file());
        }
        out.add(e);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("no dataset at " + dir + " (manifest.tsv missing)", e);
    }
    return out;
  }

  /** Recomputes every file's SHA-256 against the manifest (catches a half-written or edited dataset). */
  public static void verifyFiles(Path dir, List<Entry> entries) {
    for (Entry e : entries) {
      try (InputStream in = new DigestInputStream(new BufferedInputStream(Files.newInputStream(dir.resolve(e.file())),
          1 << 16), MessageDigest.getInstance("SHA-256"))) {
        in.transferTo(java.io.OutputStream.nullOutputStream());
        String sha = HexFormat.of().formatHex(((DigestInputStream) in).getMessageDigest().digest());
        if (!sha.equals(e.sha256())) {
          throw new IllegalStateException(e.file() + ": sha256 " + sha + " does not match the manifest " + e.sha256()
              + " - regenerate the dataset");
        }
      } catch (IOException ex) {
        throw new UncheckedIOException(ex);
      } catch (NoSuchAlgorithmException ex) {
        throw new IllegalStateException(ex);
      }
    }
  }

  public List<Loaded> load(Path dir, Targets targets, boolean replace) {
    List<Entry> entries = readManifest(dir);
    verifyFiles(dir, entries);
    Map<String, List<Entry>> byService = new LinkedHashMap<>();
    for (Entry e : entries) {
      byService.computeIfAbsent(e.service(), k -> new ArrayList<>()).add(e);
    }
    List<Loaded> out = new ArrayList<>();
    for (Map.Entry<String, List<Entry>> svc : byService.entrySet()) {
      Target t = targets.forService(svc.getKey());
      try (Connection c = DriverManager.getConnection(t.url(), t.user(), t.password())) {
        c.setAutoCommit(false);
        for (Entry e : svc.getValue()) {
          out.add(loadTable(c, dir, e, replace));
        }
      } catch (SQLException ex) {
        throw new IllegalStateException("loading " + svc.getKey() + " as " + t.user() + " failed: " + ex.getMessage(),
            ex);
      }
    }
    return out;
  }

  private Loaded loadTable(Connection c, Path dir, Entry e, boolean replace) throws SQLException {
    long t0 = System.nanoTime();
    String real = e.service() + "." + e.table();
    String cols = String.join(", ", e.columns());
    boolean exists;
    try (PreparedStatement ps = c.prepareStatement("SELECT to_regclass(?) IS NOT NULL")) {
      ps.setString(1, real);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        exists = rs.getBoolean(1);
      }
    }
    String into;
    long count;
    try (Statement st = c.createStatement()) {
      if (exists) {
        into = real;
        Set<String> missing = new HashSet<>(e.columns());
        missing.removeAll(columnsOf(c, e.service(), e.table()));
        if (!missing.isEmpty()) {
          throw new IllegalStateException(real + " exists but lacks seeded columns " + missing.stream().sorted()
              .toList() + ": update seed/ Tables." + e.table().toUpperCase(java.util.Locale.ROOT)
              + " to match the " + e.phase() + " migration (ADR-0008)");
        }
        boolean forced = forced(c, e.service(), e.table());
        if (forced) {
          st.execute("ALTER TABLE " + real + " NO FORCE ROW LEVEL SECURITY");
        }
        if (replace) {
          st.execute("TRUNCATE " + real + " CASCADE");
        } else if (hasRows(st, real)) {
          throw new IllegalStateException(real + " already has rows; re-run with --replace to reset it");
        }
        copy(c, dir, e, real, cols);
        count = count(st, real);
        if (forced) {
          st.execute("ALTER TABLE " + real + " FORCE ROW LEVEL SECURITY");
        }
      } else {
        into = "seed_stage." + e.table();
        st.execute("CREATE SCHEMA IF NOT EXISTS seed_stage");
        st.execute("REVOKE ALL ON SCHEMA seed_stage FROM PUBLIC");
        st.execute("COMMENT ON SCHEMA seed_stage IS 'Seed staging (ADR-0008): rows for tables whose migration does"
            + " not exist yet. Owner only. Not used by any service.'");
        Table spec = Tables.ALL.stream().filter(x -> x.service().equals(e.service()) && x.name().equals(e.table()))
            .findFirst().orElseThrow(() -> new IllegalStateException("unknown table in manifest: " + real));
        if (!spec.columnNames().equals(e.columns())) {
          throw new IllegalStateException(real + ": manifest columns differ from this seed build - regenerate");
        }
        // staging tables follow the dataset: recreated when the planned columns change
        st.execute("DROP TABLE IF EXISTS " + into + " CASCADE");
        st.execute(spec.stagingDdl());
        copy(c, dir, e, into, cols);
        count = count(st, into);
      }
      if (count != e.rows()) {
        throw new IllegalStateException(into + ": loaded " + count + " rows, manifest says " + e.rows());
      }
      c.commit();
      st.execute("ANALYZE " + into);
      c.commit();
    } catch (SQLException | RuntimeException ex) {
      c.rollback();
      throw ex;
    }
    long ms = (System.nanoTime() - t0) / 1_000_000;
    log.line(String.format("  %-34s %10d rows  %6d ms  (%s)", into, count, ms, exists ? "real table" : "staging"));
    return new Loaded(e.service(), e.table(), into, count, ms);
  }

  private static void copy(Connection c, Path dir, Entry e, String target, String cols) throws SQLException {
    try (InputStream in = new BufferedInputStream(Files.newInputStream(dir.resolve(e.file())), 1 << 16)) {
      long n = c.unwrap(PGConnection.class).getCopyAPI()
          .copyIn("COPY " + target + " (" + cols + ") FROM STDIN", in);
      if (n != e.rows()) {
        throw new IllegalStateException(target + ": COPY reported " + n + " rows, manifest says " + e.rows());
      }
    } catch (IOException ex) {
      throw new UncheckedIOException(ex);
    }
  }

  private static Set<String> columnsOf(Connection c, String schema, String table) throws SQLException {
    Set<String> out = new HashSet<>();
    try (PreparedStatement ps = c.prepareStatement(
        "SELECT column_name FROM information_schema.columns WHERE table_schema = ? AND table_name = ?")) {
      ps.setString(1, schema);
      ps.setString(2, table);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          out.add(rs.getString(1));
        }
      }
    }
    return out;
  }

  private static boolean forced(Connection c, String schema, String table) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement("SELECT c.relforcerowsecurity FROM pg_class c"
        + " JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = ? AND c.relname = ?")) {
      ps.setString(1, schema);
      ps.setString(2, table);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() && rs.getBoolean(1);
      }
    }
  }

  private static boolean hasRows(Statement st, String table) throws SQLException {
    try (ResultSet rs = st.executeQuery("SELECT EXISTS (SELECT 1 FROM " + table + ")")) {
      rs.next();
      return rs.getBoolean(1);
    }
  }

  private static long count(Statement st, String table) throws SQLException {
    try (ResultSet rs = st.executeQuery("SELECT count(*) FROM " + table)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private static String ident(String s) {
    if (!IDENT.matcher(s).matches()) {
      throw new IllegalStateException("manifest.tsv: not a plain identifier: " + s);
    }
    return s;
  }
}
