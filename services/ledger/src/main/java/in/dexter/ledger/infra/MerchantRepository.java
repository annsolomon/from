package in.dexter.ledger.infra;

import in.dexter.db.sql.Rows;
import in.dexter.db.sql.Sql;
import in.dexter.db.tenant.TenantScope;
import in.dexter.ledger.app.MerchantStore;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@code ledger.merchants} + {@code merchant_aliases} (global reference data; RLS shows active merchants to every
 * tenant). Trigram queries set {@code pg_trgm.similarity_threshold} for the current transaction only.
 */
public final class MerchantRepository implements MerchantStore {
  static final String HIT_COLS = "a.merchant_id, m.canonical_name, m.default_category::text AS category,"
      + " a.alias_normalized, a.weight";
  static final RowMapper<AliasHit> HIT = (rs, i) -> new AliasHit(Rows.uuid(rs, "merchant_id"),
      rs.getString("canonical_name"), rs.getString("category"), rs.getString("alias_normalized"), rs.getInt("weight"));
  /** Autocomplete: alias prefix > word prefix > substring > trigram-similar, then similarity. */
  public static final String AUTOCOMPLETE = "SELECT m.id, m.canonical_name, m.default_category::text AS category,"
      + " max(CASE WHEN a.alias_normalized LIKE :prefix THEN 3 WHEN a.alias_normalized LIKE :word THEN 2"
      + " WHEN a.alias_normalized LIKE :contains THEN 1 ELSE 0 END) AS rank,"
      + " max(public.similarity(a.alias_normalized, :q)) AS sim"
      + " FROM ledger.merchant_aliases a JOIN ledger.merchants m ON m.id = a.merchant_id"
      + " WHERE a.alias_normalized LIKE :contains OR a.alias_normalized OPERATOR(public.%) :q"
      + " GROUP BY m.id, m.canonical_name, m.default_category"
      + " ORDER BY rank DESC, sim DESC, m.canonical_name LIMIT :limit";
  /** Autocomplete's trigram threshold (looser than the resolver's: the user is still typing). */
  static final String AUTOCOMPLETE_THRESHOLD = "0.3";

  private final JdbcClient jdbc;

  public MerchantRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<AliasHit> exact(List<String> variants) {
    TenantScope.require();
    if (variants.isEmpty()) {
      return Optional.empty();
    }
    return jdbc.sql("SELECT " + HIT_COLS + " FROM unnest(CAST(:vs AS text[])) WITH ORDINALITY AS v(t, o)"
            + " JOIN ledger.merchant_aliases a ON a.alias_normalized = v.t"
            + " JOIN ledger.merchants m ON m.id = a.merchant_id"
            + " ORDER BY v.o, a.weight DESC, m.canonical_name LIMIT 1")
        .param("vs", Sql.texts(variants)).query(HIT).optional();
  }

  @Override
  public Optional<AliasHit> exactCompact(List<String> compacts) {
    TenantScope.require();
    if (compacts.isEmpty()) {
      return Optional.empty();
    }
    return jdbc.sql("SELECT " + HIT_COLS + " FROM unnest(CAST(:cs AS text[])) WITH ORDINALITY AS v(t, o)"
            + " JOIN ledger.merchant_aliases a ON a.alias_compact = v.t"
            + " JOIN ledger.merchants m ON m.id = a.merchant_id"
            + " ORDER BY v.o, a.weight DESC, m.canonical_name LIMIT 1")
        .param("cs", Sql.texts(compacts)).query(HIT).optional();
  }

  @Override
  public Optional<AliasHit> compactPrefix(String compact) {
    TenantScope.require();
    return jdbc.sql("SELECT " + HIT_COLS + " FROM ledger.merchant_aliases a"
            + " JOIN ledger.merchants m ON m.id = a.merchant_id"
            + " WHERE a.alias_compact LIKE :p ORDER BY a.weight DESC, length(a.alias_compact), m.canonical_name LIMIT 1")
        .param("p", ExpenseRepository.likeEscape(compact) + "%").query(HIT).optional();
  }

  @Override
  public List<Candidate> similar(String compact, double threshold, int limit) {
    TenantScope.require();
    threshold(String.format(Locale.ROOT, "%.2f", threshold));
    return jdbc.sql("SELECT a.merchant_id, m.canonical_name, m.default_category::text AS category, a.alias_compact,"
            + " a.weight, public.similarity(a.alias_compact, :c) AS sim"
            + " FROM ledger.merchant_aliases a JOIN ledger.merchants m ON m.id = a.merchant_id"
            + " WHERE a.alias_compact OPERATOR(public.%) :c"
            + " ORDER BY sim DESC, a.weight DESC, m.canonical_name LIMIT :limit")
        .param("c", compact).param("limit", limit)
        .query((rs, i) -> new Candidate(Rows.uuid(rs, "merchant_id"), rs.getString("canonical_name"),
            rs.getString("category"), rs.getString("alias_compact"), rs.getInt("weight"), rs.getDouble("sim")))
        .list();
  }

  @Override
  public List<Suggestion> autocomplete(String query, int limit) {
    TenantScope.require();
    threshold(AUTOCOMPLETE_THRESHOLD);
    String e = ExpenseRepository.likeEscape(query);
    return jdbc.sql(AUTOCOMPLETE)
        .param("q", query).param("prefix", e + "%").param("word", "% " + e + "%").param("contains", "%" + e + "%")
        .param("limit", limit)
        .query((rs, i) -> new Suggestion(Rows.uuid(rs, "id"), rs.getString("canonical_name"), rs.getString("category")))
        .list();
  }

  /** {@code SET LOCAL pg_trgm.similarity_threshold} (MASTER_PLAN P08's {@code set_limit}, transaction-scoped). */
  private void threshold(String value) {
    jdbc.sql("SELECT set_config('pg_trgm.similarity_threshold', :t, true)").param("t", value).query().singleRow();
  }
}
