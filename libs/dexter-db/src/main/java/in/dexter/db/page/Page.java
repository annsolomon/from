package in.dexter.db.page;

import java.util.List;
import java.util.function.Function;

/** One keyset page: at most {@code limit} items, and {@code nextCursor} null iff {@code !hasMore}. */
public record Page<T>(List<T> items, int limit, boolean hasMore, String nextCursor) {
  public Page {
    items = List.copyOf(items);
    if (hasMore == (nextCursor == null)) {
      throw new IllegalArgumentException("nextCursor must be present exactly when hasMore");
    }
  }

  public <R> Page<R> map(Function<? super T, ? extends R> f) {
    return new Page<>(items.stream().<R>map(f).toList(), limit, hasMore, nextCursor);
  }
}
