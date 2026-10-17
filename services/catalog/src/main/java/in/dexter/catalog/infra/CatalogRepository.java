package in.dexter.catalog.infra;

import in.dexter.catalog.app.CardFilter;
import in.dexter.catalog.app.CatalogStore;
import in.dexter.db.page.KeyType;
import in.dexter.db.page.KeysetPager;
import in.dexter.db.page.KeysetQuery;
import in.dexter.db.page.Page;
import in.dexter.db.page.PageRequest;
import in.dexter.db.sql.Rows;
import in.dexter.db.sql.Sql;
import in.dexter.db.tenant.TenantScope;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Public catalog reads on the catalog_app pool (RLS: published cards and their rules, active offers). The card list
 * is keyset-paged on {@code (name, id)} ascending over published rows ({@code card_products_public}); each filter
 * combination is its own static query (as in ledger, ADR-0012).
 */
public final class CatalogRepository implements CatalogStore {
  static final RowMapper<PublishedCard> CARD = (rs, i) -> new PublishedCard(Rows.uuid(rs, "id"), rs.getString("slug"),
      rs.getString("name"), rs.getInt("cv"), rs.getString("snapshot"));
  static final String BASE = "SELECT c.id, c.slug, c.name, v.version AS cv, v.snapshot::text AS snapshot"
      + " FROM catalog.card_products c JOIN catalog.card_product_versions v"
      + " ON v.card_product_id = c.id AND v.effective_to IS NULL WHERE c.status = 'published'";
  /**
   * "Earns at least :minRateBps on :category": the most specific rule of the card's current version that covers the
   * category (a rule path that is an ancestor-or-self, or the base rule) and does not exclude it, pays that much.
   * Written with NOT EXISTS (no ORDER BY: keyset base SQL must not contain one). Merchant rules are not category rates.
   */
  static String applicable(String r) {
    return r + ".card_product_id = c.id AND " + r + ".version = c.current_version AND " + r + ".merchant_id IS NULL"
        + " AND (" + r + ".category_path IS NULL OR CAST(:category AS public.ltree) OPERATOR(public.<@) " + r
        + ".category_path) AND NOT (coalesce(" + r + ".excluded_paths, CAST('{}' AS public.ltree[]))"
        + " OPERATOR(public.@>) CAST(:category AS public.ltree))";
  }

  static String level(String r) {
    return "public.nlevel(coalesce(" + r + ".category_path, CAST('' AS public.ltree)))";
  }

  static final List<String> PREDICATES = List.of(
      " AND c.network = :network",
      " AND c.issuer_id = (SELECT i.id FROM catalog.issuers i WHERE i.code = :issuer)",
      " AND c.annual_fee_paise <= :maxAnnualFee",
      " AND EXISTS (SELECT 1 FROM catalog.reward_rules r WHERE " + applicable("r") + " AND r.rate_bps >= :minRateBps"
          + " AND NOT EXISTS (SELECT 1 FROM catalog.reward_rules r2 WHERE " + applicable("r2") + " AND " + level("r2")
          + " > " + level("r") + "))");
  /**
   * Search: trigram on the lower-cased name (GIN), substring or similar, most similar first. Runs through the owner's
   * SECURITY DEFINER function: under catalog_app's policy the planner may not use the index for LIKE/% (not
   * leakproof) and would scan the table (ADR-0014 §6).
   */
  public static final String SEARCH = "SELECT id, slug, name, network, tier, annual_fee_paise, sim"
      + " FROM catalog.search_published_cards(:q, :limit)";

  private final JdbcClient jdbc;
  private final KeysetPager pager;
  private final Map<Integer, KeysetQuery<PublishedCard>> queries = new ConcurrentHashMap<>();

  public CatalogRepository(JdbcClient jdbc, KeysetPager pager) {
    this.jdbc = jdbc;
    this.pager = pager;
  }

  public static KeysetQuery<PublishedCard> listQuery(int mask) {
    StringBuilder sql = new StringBuilder(BASE);
    for (int i = 0; i < PREDICATES.size(); i++) {
      if ((mask & (1 << i)) != 0) {
        sql.append(PREDICATES.get(i));
      }
    }
    return KeysetQuery.<PublishedCard>builder("name_asc")
        .direction(KeysetQuery.Direction.ASC)
        .key("c.name", KeyType.TEXT)
        .key("c.id", KeyType.UUID)
        .sql(sql.toString())
        .mapper(CARD)
        .keysOf(c -> List.of(c.name(), c.id()))
        .build();
  }

  /** A category without minRateBps means "earns anything on it" (≥ 0 bps). */
  public static int mask(CardFilter f) {
    Object[] v = {f.network(), f.issuer(), f.maxAnnualFee(), f.category()};
    int m = 0;
    for (int i = 0; i < v.length; i++) {
      if (v[i] != null) {
        m |= 1 << i;
      }
    }
    return m;
  }

  @Override
  public Page<PublishedCard> list(PageRequest page, CardFilter f) {
    TenantScope.require();
    Map<String, Object> p = new HashMap<>();
    put(p, "network", f.network());
    put(p, "issuer", f.issuer());
    put(p, "maxAnnualFee", f.maxAnnualFee());
    if (f.category() != null) {
      p.put("category", f.category());
      p.put("minRateBps", f.minRateBps() == null ? 0 : f.minRateBps());
    }
    return pager.page(jdbc, queries.computeIfAbsent(mask(f), CatalogRepository::listQuery), page, p, f.asMap());
  }

  @Override
  public Optional<PublishedCard> bySlug(String slug) {
    TenantScope.require();
    return jdbc.sql(BASE + " AND c.slug = :slug").param("slug", slug).query(CARD).optional();
  }

  @Override
  public List<SearchHit> search(String q, int limit) {
    TenantScope.require();
    return jdbc.sql(SEARCH)
        .param("q", q).param("limit", limit)
        .query((rs, i) -> new SearchHit(Rows.uuid(rs, "id"), rs.getString("slug"), rs.getString("name"),
            rs.getString("network"), rs.getString("tier"), rs.getLong("annual_fee_paise"), rs.getDouble("sim")))
        .list();
  }

  @Override
  public List<Potential> potential(String category, int limit) {
    TenantScope.require();
    return jdbc.sql("SELECT x.* FROM (SELECT DISTINCT ON (c.id) c.id, c.slug, c.name, r.reward_type, r.rate_bps,"
            + " r.cap_paise, r.cap_period, r.category_path::text AS rule_path"
            + " FROM catalog.card_products c JOIN catalog.reward_rules r ON r.card_product_id = c.id"
            + " AND r.version = c.current_version"
            + " WHERE c.status = 'published' AND r.merchant_id IS NULL AND upper_inf(r.valid)"
            + " AND (r.category_path IS NULL OR CAST(:category AS public.ltree) OPERATOR(public.<@) r.category_path)"
            + " AND NOT (coalesce(r.excluded_paths, CAST('{}' AS public.ltree[])) OPERATOR(public.@>)"
            + " CAST(:category AS public.ltree))"
            + " ORDER BY c.id, public.nlevel(coalesce(r.category_path, CAST('' AS public.ltree))) DESC,"
            + " r.priority DESC) x ORDER BY x.rate_bps DESC, x.name, x.id LIMIT :limit")
        .param("category", category).param("limit", limit)
        .query((rs, i) -> new Potential(Rows.uuid(rs, "id"), rs.getString("slug"), rs.getString("name"),
            rs.getString("reward_type"), rs.getInt("rate_bps"), Rows.nullableLong(rs, "cap_paise"),
            rs.getString("cap_period"), rs.getString("rule_path")))
        .list();
  }

  @Override
  public List<Offer> activeOffers(UUID merchantId, String category, Instant at, int limit) {
    TenantScope.require();
    StringBuilder sql = new StringBuilder("SELECT o.id, o.issuer_id, o.card_product_id, o.merchant_id,"
        + " o.category_path::text AS category_path, o.title, o.kind, o.value_bps, o.value_paise, o.max_discount_paise,"
        + " o.min_spend_paise, lower(o.valid) AS valid_from, upper(o.valid) AS valid_to, o.stackable, o.terms_url,"
        + " o.version FROM catalog.offers o WHERE o.status = 'active' AND o.valid @> CAST(:at AS timestamptz)");
    Map<String, Object> p = new HashMap<>();
    p.put("at", Sql.ts(at));
    if (merchantId != null) {
      sql.append(" AND o.merchant_id = :m");
      p.put("m", merchantId);
    }
    if (category != null) {
      sql.append(" AND o.category_path IS NOT NULL AND CAST(:c AS public.ltree) OPERATOR(public.<@) o.category_path");
      p.put("c", category);
    }
    sql.append(" ORDER BY coalesce(o.value_bps, 0) DESC, coalesce(o.value_paise, 0) DESC, o.id LIMIT :limit");
    p.put("limit", limit);
    return jdbc.sql(sql.toString()).params(p)
        .query((rs, i) -> new Offer(Rows.uuid(rs, "id"), Rows.uuid(rs, "issuer_id"), Rows.uuid(rs, "card_product_id"),
            Rows.uuid(rs, "merchant_id"), rs.getString("category_path"), rs.getString("title"), rs.getString("kind"),
            Rows.nullableInt(rs, "value_bps"), Rows.nullableLong(rs, "value_paise"),
            Rows.nullableLong(rs, "max_discount_paise"), rs.getLong("min_spend_paise"), Rows.instant(rs, "valid_from"),
            Rows.instant(rs, "valid_to"), rs.getBoolean("stackable"), rs.getString("terms_url"), rs.getInt("version")))
        .list();
  }

  private static void put(Map<String, Object> m, String k, Object v) {
    if (v != null) {
      m.put(k, v);
    }
  }
}
