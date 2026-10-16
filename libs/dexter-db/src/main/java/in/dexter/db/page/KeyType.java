package in.dexter.db.page;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** How a sort-key value is written into a cursor and bound back into SQL. */
public enum KeyType {
  TIMESTAMPTZ("timestamptz"),
  UUID("uuid"),
  BIGINT("bigint"),
  INTEGER("integer"),
  TEXT("text"),
  DATE("date");

  private final String sqlType;

  KeyType(String sqlType) {
    this.sqlType = sqlType;
  }

  public String sqlType() {
    return sqlType;
  }

  /** Java value (from the mapped row) → cursor string. */
  public String encode(Object v) {
    if (v == null) {
      throw new IllegalArgumentException("keyset sort keys must be NOT NULL columns");
    }
    return switch (this) {
      case TIMESTAMPTZ -> switch (v) {
        case Instant i -> i.toString();
        case OffsetDateTime o -> o.toInstant().toString();
        default -> throw new IllegalArgumentException("expected Instant, got " + v.getClass());
      };
      default -> v.toString();
    };
  }

  /** Cursor string → JDBC bind value. Throws IllegalArgumentException on garbage. */
  public Object decode(String s) {
    try {
      return switch (this) {
        case TIMESTAMPTZ -> Instant.parse(s).atOffset(ZoneOffset.UTC);
        case UUID -> java.util.UUID.fromString(s);
        case BIGINT -> Long.parseLong(s);
        case INTEGER -> Integer.parseInt(s);
        case TEXT -> s;
        case DATE -> LocalDate.parse(s);
      };
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("bad " + name() + " cursor key", e);
    }
  }
}
