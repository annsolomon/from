package in.dexter.ledger.infra;

import in.dexter.core.error.DexterException;
import in.dexter.db.sql.Rows;
import in.dexter.db.tenant.TenantScope;
import in.dexter.ledger.app.CategoryStore;
import in.dexter.ledger.domain.LedgerErrors;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/** {@code ledger.categories}: RLS shows the system tree plus the tenant's own rows. */
public final class CategoryRepository implements CategoryStore {
  static final RowMapper<Category> ROW = (rs, i) -> new Category(Rows.uuid(rs, "id"), rs.getString("path"),
      rs.getString("name"), rs.getString("icon"), rs.getBoolean("is_system"));
  private final JdbcClient jdbc;

  public CategoryRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<Category> listVisible() {
    TenantScope.require();
    return jdbc.sql("SELECT id, path::text AS path, name, icon, is_system FROM ledger.categories ORDER BY path, is_system DESC")
        .query(ROW).list();
  }

  @Override
  public Optional<Category> findVisible(String path) {
    TenantScope.require();
    return jdbc.sql("SELECT id, path::text AS path, name, icon, is_system FROM ledger.categories"
            + " WHERE path = CAST(:p AS public.ltree) ORDER BY is_system DESC LIMIT 1")
        .param("p", path).query(ROW).optional();
  }

  @Override
  public void insert(UUID id, UUID accountId, String path, String name, String icon) {
    TenantScope.require();
    try {
      jdbc.sql("INSERT INTO ledger.categories (id, account_id, path, name, icon, is_system)"
              + " VALUES (:id, :a, CAST(:p AS public.ltree), :n, :i, false)")
          .param("id", id).param("a", accountId).param("p", path).param("n", name).param("i", icon).update();
    } catch (DuplicateKeyException e) {
      throw new DexterException(LedgerErrors.CATEGORY_EXISTS, "Category " + path + " already exists");
    }
  }
}
