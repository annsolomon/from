package in.dexter.identity.infra;

import in.dexter.db.tenant.TenantScope;
import in.dexter.identity.app.KekRewrapStore;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;

/** {@code identity.kek_stale / kek_swap / kek_usage} (SECURITY DEFINER, V202611121000). */
public final class KekRewrapRepository implements KekRewrapStore {
  private final JdbcClient jdbc;

  public KekRewrapRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<Wrapped> stale(String active, @Nullable String from, int limit) {
    TenantScope.require();
    return jdbc.sql("SELECT kind, id, kek_id, sealed FROM identity.kek_stale(:active, :from, :limit)")
        .param("active", active).param("from", from).param("limit", limit)
        .query((rs, i) -> new Wrapped(rs.getString("kind"), rs.getString("id"), rs.getString("kek_id"),
            rs.getBytes("sealed")))
        .list();
  }

  @Override
  public boolean swap(Wrapped row, String newKek, byte[] sealed) {
    TenantScope.require();
    return Boolean.TRUE.equals(jdbc.sql("SELECT identity.kek_swap(:kind, :id, :old, :new, :sealed)")
        .param("kind", row.kind()).param("id", row.id()).param("old", row.kekId()).param("new", newKek)
        .param("sealed", sealed).query(Boolean.class).single());
  }

  @Override
  public List<Usage> usage() {
    TenantScope.require();
    return jdbc.sql("SELECT kind, kek_id, n FROM identity.kek_usage() ORDER BY kind, kek_id")
        .query((rs, i) -> new Usage(rs.getString("kind"), rs.getString("kek_id"), rs.getLong("n")))
        .list();
  }
}
