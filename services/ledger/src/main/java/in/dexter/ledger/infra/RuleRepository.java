package in.dexter.ledger.infra;

import in.dexter.db.sql.Rows;
import in.dexter.db.tenant.TenantScope;
import in.dexter.ledger.app.RuleStore;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** {@code ledger.categorization_rules} of the current tenant (RLS). */
public final class RuleRepository implements RuleStore {
  private final JdbcClient jdbc;

  public RuleRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<Rule> list() {
    TenantScope.require();
    return jdbc.sql("SELECT id, match_type, pattern, category_path::text AS category_path, priority, created_at"
            + " FROM ledger.categorization_rules WHERE account_id = (SELECT ledger.app_account())"
            + " ORDER BY priority, id")
        .query((rs, i) -> new Rule(Rows.uuid(rs, "id"), rs.getString("match_type"), rs.getString("pattern"),
            rs.getString("category_path"), rs.getInt("priority"), Rows.instant(rs, "created_at")))
        .list();
  }

  @Override
  public int count() {
    TenantScope.require();
    return jdbc.sql("SELECT count(1) FROM ledger.categorization_rules WHERE account_id = (SELECT ledger.app_account())")
        .query(Integer.class).single();
  }

  @Override
  public void insert(UUID id, UUID accountId, String matchType, String pattern, String categoryPath, int priority) {
    TenantScope.require();
    jdbc.sql("INSERT INTO ledger.categorization_rules (id, account_id, match_type, pattern, category_path, priority)"
            + " VALUES (:id, :a, :t, :p, CAST(:c AS public.ltree), :pr)")
        .param("id", id).param("a", accountId).param("t", matchType).param("p", pattern).param("c", categoryPath)
        .param("pr", priority)
        .update();
  }

  @Override
  public boolean delete(UUID id) {
    TenantScope.require();
    return jdbc.sql("DELETE FROM ledger.categorization_rules WHERE id = :id").param("id", id).update() == 1;
  }
}
