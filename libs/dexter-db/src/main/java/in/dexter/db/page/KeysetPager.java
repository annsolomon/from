package in.dexter.db.page;

import in.dexter.core.error.CoreErrors;
import in.dexter.core.error.DexterException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Executes a {@link KeysetQuery} (MASTER_PLAN 4.3): {@code LIMIT limit+1}, {@code hasMore = rows >
 * limit}, the extra row trimmed, and the next cursor signed from the last row's keys. No offset paging and
 * no COUNT. Call it inside {@code TenantTx.read} so RLS applies.
 */
public final class KeysetPager {
  private final KeysetCursorCodec codec;

  public KeysetPager(KeysetCursorCodec codec) {
    this.codec = codec;
  }

  /**
   * @param params  named parameters used by the query's WHERE clause
   * @param filters the user-visible filters the cursor is bound to (usually the same values)
   */
  public <T> Page<T> page(JdbcClient jdbc, KeysetQuery<T> q, PageRequest req, Map<String, ?> params,
      Map<String, ?> filters) {
    String fhash = KeysetCursorCodec.filterHash(filters);
    Map<String, Object> bind = new HashMap<>(params == null ? Map.of() : params);
    for (String k : bind.keySet()) {
      if (k.startsWith("__")) {
        throw new IllegalArgumentException("parameter names starting with __ are reserved: " + k);
      }
    }
    String sql;
    if (req.isFirst()) {
      sql = q.firstPageSql();
    } else {
      CursorPosition pos = codec.decode(req.cursor(), q.sort(), fhash);
      if (pos.keys().size() != q.keys().size()) {
        throw new DexterException(CoreErrors.CURSOR_INVALID, "The cursor is invalid or has expired");
      }
      for (int i = 0; i < pos.keys().size(); i++) {
        try {
          bind.put(KeysetQuery.keyParam(i), q.keys().get(i).type().decode(pos.keys().get(i)));
        } catch (IllegalArgumentException e) {
          throw new DexterException(CoreErrors.CURSOR_INVALID, "The cursor is invalid or has expired");
        }
      }
      sql = q.nextPageSql();
    }
    bind.put(KeysetQuery.LIMIT_PARAM, req.limit() + 1);
    List<T> rows = new ArrayList<>(jdbc.sql(sql).params(bind).query(q.mapper()).list());
    boolean hasMore = rows.size() > req.limit();
    if (hasMore) {
      rows = new ArrayList<>(rows.subList(0, req.limit()));
    }
    String next = null;
    if (hasMore) {
      T last = rows.getLast();
      List<Object> values = q.keyValues(last);
      List<String> encoded = new ArrayList<>();
      for (int i = 0; i < q.keys().size(); i++) {
        encoded.add(q.keys().get(i).type().encode(values.get(i)));
      }
      next = codec.encode(q.sort(), encoded, fhash);
    }
    return new Page<>(rows, req.limit(), hasMore, next);
  }
}
