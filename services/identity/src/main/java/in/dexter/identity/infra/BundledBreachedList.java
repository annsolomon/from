package in.dexter.identity.infra;

import in.dexter.identity.domain.PasswordPolicy;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * The bundled breached-password list ({@code breached-passwords.txt.gz}, see its NOTICE): the NCSC top-100k
 * passwords that are long enough to pass the length rule, matched case-insensitively. Offline, loaded once.
 */
public final class BundledBreachedList implements PasswordPolicy.BreachedList {
  public static final String RESOURCE = "/breached-passwords.txt.gz";
  private final Set<String> entries;

  public BundledBreachedList() {
    Set<String> s = new HashSet<>(16_384);
    try (InputStream raw = BundledBreachedList.class.getResourceAsStream(RESOURCE)) {
      if (raw == null) {
        throw new IllegalStateException("missing resource " + RESOURCE);
      }
      try (BufferedReader r = new BufferedReader(new InputStreamReader(new GZIPInputStream(raw), StandardCharsets.UTF_8))) {
        String line;
        while ((line = r.readLine()) != null) {
          if (!line.isEmpty()) {
            s.add(line);
          }
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    this.entries = Set.copyOf(s);
  }

  public int size() {
    return entries.size();
  }

  @Override
  public boolean contains(String password) {
    return password != null && entries.contains(password.strip().toLowerCase(Locale.ROOT));
  }
}
