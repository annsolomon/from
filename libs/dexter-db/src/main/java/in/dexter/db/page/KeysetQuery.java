package in.dexter.db.page;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;
import org.springframework.jdbc.core.RowMapper;

/**
 * A keyset-paged query definition (MASTER_PLAN 4.3). The caller gives the SELECT ... FROM ...
 * WHERE part (tenant predicate + fixed filters); the pager appends the keyset predicate, the ORDER BY
 * and {@code LIMIT limit+1}, so the ORDER BY always matches the sort keys and the index.
 *
 * <p>Two SQL variants are generated (first page, next page) instead of an {@code :after IS NULL OR}
 * predicate, so the planner always sees a clean range condition. All sort keys share one direction
 * (row comparison), and the last key must be unique (normally {@code id}) for a total order.
 */
public final class KeysetQuery<T> {
  public enum Direction { ASC, DESC }

  static final String LIMIT_PARAM = "__limit";

  private final String sort;
  private final Direction direction;
  private final List<SortKey> keys;
  private final String selectFromWhere;
  private final RowMapper<T> mapper;
  private final Function<T, List<Object>> keyExtractor;
  private final String firstSql;
  private final String nextSql;

  private KeysetQuery(String sort, Direction direction, List<SortKey> keys, String selectFromWhere,
      RowMapper<T> mapper, Function<T, List<Object>> keyExtractor) {
    this.sort = Objects.requireNonNull(sort);
    this.direction = Objects.requireNonNull(direction);
    this.keys = List.copyOf(keys);
    this.selectFromWhere = selectFromWhere.strip();
    this.mapper = Objects.requireNonNull(mapper);
    this.keyExtractor = Objects.requireNonNull(keyExtractor);
    if (this.keys.isEmpty()) {
      throw new IllegalArgumentException("at least one sort key");
    }
    String upper = this.selectFromWhere.toUpperCase(Locale.ROOT);
    if (!upper.matches("(?s).*\\bWHERE\\b.*")) {
      throw new IllegalArgumentException("keyset base SQL must contain a WHERE clause (tenant predicate)");
    }
    if (upper.matches("(?s).*\\bORDER\\s+BY\\b.*") || upper.matches("(?s).*\\bLIMIT\\b.*")) {
      throw new IllegalArgumentException("keyset base SQL must not contain ORDER BY or LIMIT");
    }
    String order = " ORDER BY " + String.join(", ",
        this.keys.stream().map(k -> k.column() + " " + direction.name()).toList());
    String limit = " LIMIT :" + LIMIT_PARAM;
    this.firstSql = this.selectFromWhere + order + limit;
    List<String> cols = new ArrayList<>();
    List<String> params = new ArrayList<>();
    for (int i = 0; i < this.keys.size(); i++) {
      cols.add(this.keys.get(i).column());
      params.add("CAST(:" + keyParam(i) + " AS " + this.keys.get(i).type().sqlType() + ")");
    }
    String op = direction == Direction.DESC ? " < " : " > ";
    String predicate = this.keys.size() == 1
        ? cols.getFirst() + op + params.getFirst()
        : "(" + String.join(", ", cols) + ")" + op + "(" + String.join(", ", params) + ")";
    this.nextSql = this.selectFromWhere + " AND " + predicate + order + limit;
  }

  public static <T> Builder<T> builder(String sort) {
    return new Builder<>(sort);
  }

  static String keyParam(int i) {
    return "__k" + i;
  }

  public String sort() {
    return sort;
  }

  public List<SortKey> keys() {
    return keys;
  }

  public String firstPageSql() {
    return firstSql;
  }

  public String nextPageSql() {
    return nextSql;
  }

  RowMapper<T> mapper() {
    return mapper;
  }

  List<Object> keyValues(T row) {
    return keyExtractor.apply(row);
  }

  public static final class Builder<T> {
    private final String sort;
    private Direction direction = Direction.DESC;
    private final List<SortKey> keys = new ArrayList<>();
    private String sql;
    private RowMapper<T> mapper;
    private Function<T, List<Object>> extractor;

    private Builder(String sort) {
      this.sort = sort;
    }

    public Builder<T> direction(Direction d) {
      this.direction = d;
      return this;
    }

    public Builder<T> key(String column, KeyType type) {
      keys.add(new SortKey(column, type));
      return this;
    }

    public Builder<T> sql(String selectFromWhere) {
      this.sql = selectFromWhere;
      return this;
    }

    public Builder<T> mapper(RowMapper<T> mapper) {
      this.mapper = mapper;
      return this;
    }

    /** Extracts the sort-key values from a mapped row, in key order. */
    public Builder<T> keysOf(Function<T, List<Object>> extractor) {
      this.extractor = extractor;
      return this;
    }

    public KeysetQuery<T> build() {
      return new KeysetQuery<>(sort, direction, keys, Objects.requireNonNull(sql, "sql"), mapper, extractor);
    }
  }
}
