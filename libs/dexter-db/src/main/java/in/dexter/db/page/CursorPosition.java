package in.dexter.db.page;

import java.util.List;

/** A verified cursor: the sort it belongs to, the key values of the last row, and the key id used. */
public record CursorPosition(String sort, List<String> keys, String kid) {
  public CursorPosition {
    keys = List.copyOf(keys);
  }
}
