package in.dexter.db.plan;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Query-plan assertions (MASTER_PLAN 3.5 rule 9). Runs {@code EXPLAIN (FORMAT JSON)} with the real
 * bound parameters and fails when a listed big table is read by a {@code Seq Scan}, when a
 * {@code Sort} appears although the index should deliver the order, or when a required index is not
 * used. The plan JSON is analysed by Postgres jsonpath, so no JSON library is needed.
 *
 * <p>Run inside {@code TenantTx.read} so the RLS predicate is part of the plan, on the medium seed
 * (≥ 1M rows) with fresh statistics.
 */
public final class PlanAssert {
  private final JdbcClient jdbc;
  private final String sql;
  private final Map<String, ?> params;
  private final Set<String> noSeqScan = new LinkedHashSet<>();
  private final Set<String> requiredIndexes = new LinkedHashSet<>();
  private boolean noSort;

  private PlanAssert(JdbcClient jdbc, String sql, Map<String, ?> params) {
    this.jdbc = jdbc;
    this.sql = sql;
    this.params = params == null ? Map.of() : params;
  }

  public static PlanAssert query(JdbcClient jdbc, String sql, Map<String, ?> params) {
    return new PlanAssert(jdbc, sql, params);
  }

  /** Tables (unqualified relation names) that must never be sequentially scanned. */
  public PlanAssert forbidSeqScanOn(String... tables) {
    noSeqScan.addAll(List.of(tables));
    return this;
  }

  /** No Sort / Incremental Sort node anywhere: the index must deliver the ORDER BY. */
  public PlanAssert forbidSort() {
    noSort = true;
    return this;
  }

  /** At least one plan node must use each of these indexes. */
  public PlanAssert requireIndex(String... indexes) {
    requiredIndexes.addAll(List.of(indexes));
    return this;
  }

  /** Returns the plan JSON when every rule holds; throws {@link AssertionError} otherwise. */
  public String verify() {
    String plan = explain();
    List<String> problems = new ArrayList<>();
    for (String t : strings(plan, "strict $.** ? (@.\"Node Type\" == \"Seq Scan\").\"Relation Name\"")) {
      if (noSeqScan.contains(t)) {
        problems.add("Seq Scan on " + t);
      }
    }
    if (noSort) {
      Set<String> sorts = strings(plan,
          "strict $.** ? (@.\"Node Type\" == \"Sort\" || @.\"Node Type\" == \"Incremental Sort\").\"Node Type\"");
      if (!sorts.isEmpty()) {
        problems.add("plan contains " + sorts);
      }
    }
    Set<String> used = strings(plan, "strict $.**.\"Index Name\"");
    for (String idx : requiredIndexes) {
      if (!used.contains(idx)) {
        problems.add("index " + idx + " not used (used: " + used + ")");
      }
    }
    if (!problems.isEmpty()) {
      throw new AssertionError("PlanAssert failed: " + problems + "\nSQL: " + sql + "\nPLAN: " + plan);
    }
    return plan;
  }

  /** Node types in the plan, for diagnostics. */
  public Set<String> nodeTypes() {
    return strings(explain(), "strict $.**.\"Node Type\"");
  }

  private String explain() {
    return jdbc.sql("EXPLAIN (FORMAT JSON) " + sql).params(params)
        .query((rs, n) -> rs.getString(1)).single();
  }

  private Set<String> strings(String plan, String path) {
    return new LinkedHashSet<>(jdbc
        .sql("SELECT DISTINCT v #>> '{}' FROM jsonb_path_query(CAST(:p AS jsonb), CAST(:q AS jsonpath)) AS v")
        .param("p", plan).param("q", path)
        .query((rs, n) -> rs.getString(1)).list());
  }
}
