package in.dexter.db.autoconfigure;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code dexter.cursor.active-kid} and {@code dexter.cursor.keys.<kid>=<base64, ≥32 bytes>}
 * (MASTER_PLAN 4.2). Secrets come from the environment ({@code DEXTER_CURSOR_KEYS_C1}), never from Git.
 */
@ConfigurationProperties("dexter.cursor")
public record CursorProperties(String activeKid, Map<String, String> keys) {
  public CursorProperties {
    keys = keys == null ? Map.of() : Map.copyOf(keys);
  }
}
