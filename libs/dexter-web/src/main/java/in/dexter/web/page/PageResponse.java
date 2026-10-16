package in.dexter.web.page;

import in.dexter.db.page.Page;
import java.util.List;
import java.util.function.Function;

/**
 * List response envelope (MASTER_PLAN 4.1): {@code {"data":[...],"page":{"limit":50,"hasMore":true,
 * "nextCursor":"..."}}}. No totals.
 */
public record PageResponse<T>(List<T> data, PageMeta page) {
  public record PageMeta(int limit, boolean hasMore, String nextCursor) {}

  public static <T> PageResponse<T> from(Page<T> p) {
    return new PageResponse<>(p.items(), new PageMeta(p.limit(), p.hasMore(), p.nextCursor()));
  }

  public static <S, T> PageResponse<T> from(Page<S> p, Function<? super S, ? extends T> mapper) {
    return from(p.map(mapper));
  }
}
