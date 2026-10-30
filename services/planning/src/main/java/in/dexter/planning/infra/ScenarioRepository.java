package in.dexter.planning.infra;

import in.dexter.db.page.KeyType;
import in.dexter.db.page.KeysetPager;
import in.dexter.db.page.KeysetQuery;
import in.dexter.db.page.Page;
import in.dexter.db.page.PageRequest;
import in.dexter.db.sql.Rows;
import in.dexter.db.sql.Sql;
import in.dexter.db.tenant.TenantScope;
import in.dexter.planning.app.ScenarioStore;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Saved scenarios (RLS: the tenant's rows). The two jsonb columns come back as text and are parsed in the app. */
public final class ScenarioRepository implements ScenarioStore {
  static final String COLS = "id, account_id, name, base_snapshot, adjustments, created_at, updated_at, version";
  static final String SELECT = "id, account_id, name, base_snapshot::text AS base_snapshot,"
      + " adjustments::text AS adjustments, created_at, updated_at, version";
  static final RowMapper<ScenarioRow> SCENARIO = (rs, n) -> new ScenarioRow(Rows.uuid(rs, "id"),
      Rows.uuid(rs, "account_id"), rs.getString("name"), rs.getString("base_snapshot"),
      rs.getString("adjustments"), Rows.instant(rs, "created_at"), Rows.instant(rs, "updated_at"),
      rs.getInt("version"));

  static final KeysetQuery<ScenarioRow> LIST = KeysetQuery.<ScenarioRow>builder("created_desc")
      .direction(KeysetQuery.Direction.DESC)
      .key("created_at", KeyType.TIMESTAMPTZ)
      .key("id", KeyType.UUID)
      .sql("SELECT " + SELECT + " FROM planning.scenarios WHERE account_id = (SELECT planning.app_account())")
      .mapper(SCENARIO)
      .keysOf(s -> List.of(s.createdAt(), s.id()))
      .build();

  private final JdbcClient jdbc;
  private final KeysetPager pager;

  public ScenarioRepository(JdbcClient jdbc, KeysetPager pager) {
    this.jdbc = jdbc;
    this.pager = pager;
  }

  @Override
  public void insert(ScenarioRow s) {
    TenantScope.require();
    jdbc.sql("INSERT INTO planning.scenarios (" + COLS + ") VALUES (:id, :a, :name, :base, :adj, :now, :now, 0)")
        .param("id", s.id()).param("a", s.accountId()).param("name", s.name())
        .param("base", Sql.jsonb(s.baseSnapshot())).param("adj", Sql.jsonb(s.adjustments()))
        .param("now", Sql.ts(s.createdAt())).update();
  }

  @Override
  public Optional<ScenarioRow> find(UUID id) {
    TenantScope.require();
    return jdbc.sql("SELECT " + SELECT + " FROM planning.scenarios WHERE id = :id").param("id", id)
        .query(SCENARIO).optional();
  }

  @Override
  public Optional<ScenarioRow> lock(UUID id) {
    TenantScope.require();
    return jdbc.sql("SELECT " + SELECT + " FROM planning.scenarios WHERE id = :id FOR UPDATE").param("id", id)
        .query(SCENARIO).optional();
  }

  @Override
  public void update(ScenarioRow s) {
    TenantScope.require();
    jdbc.sql("UPDATE planning.scenarios SET name = :name, adjustments = :adj, updated_at = :now, version = :v"
            + " WHERE id = :id")
        .param("name", s.name()).param("adj", Sql.jsonb(s.adjustments())).param("now", Sql.ts(s.updatedAt()))
        .param("v", s.version()).param("id", s.id()).update();
  }

  @Override
  public Page<ScenarioRow> list(PageRequest page) {
    TenantScope.require();
    return pager.page(jdbc, LIST, page, Map.of(), Map.of());
  }
}
