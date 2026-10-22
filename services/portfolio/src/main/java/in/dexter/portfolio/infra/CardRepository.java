package in.dexter.portfolio.infra;

import in.dexter.db.page.KeyType;
import in.dexter.db.page.KeysetPager;
import in.dexter.db.page.KeysetQuery;
import in.dexter.db.page.Page;
import in.dexter.db.page.PageRequest;
import in.dexter.db.sql.Rows;
import in.dexter.db.sql.Sql;
import in.dexter.db.tenant.TenantScope;
import in.dexter.events.ProjectionVersionGuard;
import in.dexter.portfolio.app.CardStore;
import in.dexter.portfolio.app.ProjectionStore;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/** User cards and the catalog projection (RLS: the tenant's cards; every tenant reads the projection). */
public final class CardRepository implements CardStore, ProjectionStore {
  static final String COLS = "id, account_id, card_product_id, nickname, last4, network, statement_day,"
      + " due_offset_days, credit_limit_paise, opened_on, status, created_at, updated_at, removed_at, version";
  static final RowMapper<CardRow> CARD = (rs, n) -> new CardRow(Rows.uuid(rs, "id"), Rows.uuid(rs, "account_id"),
      Rows.uuid(rs, "card_product_id"), rs.getString("nickname"), rs.getString("last4"), rs.getString("network"),
      rs.getInt("statement_day"), rs.getInt("due_offset_days"), Rows.nullableLong(rs, "credit_limit_paise"),
      Rows.date(rs, "opened_on"), rs.getString("status"), Rows.instant(rs, "created_at"),
      Rows.instant(rs, "updated_at"), Rows.instant(rs, "removed_at"), rs.getInt("version"));
  static final RowMapper<Product> PRODUCT = (rs, n) -> new Product(Rows.uuid(rs, "card_product_id"),
      rs.getString("slug"), rs.getString("name"), rs.getString("issuer_code"), rs.getString("network"),
      rs.getString("tier"), rs.getLong("annual_fee_paise"), rs.getString("status"), rs.getInt("current_version"),
      rs.getString("snapshot"));

  /** Live cards newest first (user_cards_list). */
  static final KeysetQuery<CardRow> LIST = KeysetQuery.<CardRow>builder("created_desc")
      .direction(KeysetQuery.Direction.DESC)
      .key("created_at", KeyType.TIMESTAMPTZ)
      .key("id", KeyType.UUID)
      .sql("SELECT " + COLS + " FROM portfolio.user_cards WHERE account_id = (SELECT portfolio.app_account())"
          + " AND status <> 'removed'")
      .mapper(CARD)
      .keysOf(c -> List.of(c.createdAt(), c.id()))
      .build();

  /** The guarded projection upsert (MASTER_PLAN 6.4: only a newer aggregate version writes). */
  static final String UPSERT_PRODUCT = "INSERT INTO portfolio.catalog_products_proj (card_product_id, slug, name,"
      + " issuer_code, network, tier, annual_fee_paise, status, current_version, snapshot, projected_version,"
      + " updated_at) VALUES (:id, :slug, :name, :issuer, :network, :tier, :fee, :status, :cv, CAST(:snap AS jsonb),"
      + " :version, now())"
      + " ON CONFLICT (card_product_id) DO UPDATE SET slug = EXCLUDED.slug, name = EXCLUDED.name,"
      + " issuer_code = EXCLUDED.issuer_code, network = EXCLUDED.network, tier = EXCLUDED.tier,"
      + " annual_fee_paise = EXCLUDED.annual_fee_paise, status = EXCLUDED.status,"
      + " current_version = EXCLUDED.current_version, snapshot = EXCLUDED.snapshot,"
      + " projected_version = EXCLUDED.projected_version, updated_at = now()"
      + " WHERE portfolio.catalog_products_proj.projected_version < EXCLUDED.projected_version";

  private final JdbcClient jdbc;
  private final KeysetPager pager;

  public CardRepository(JdbcClient jdbc, KeysetPager pager) {
    this.jdbc = jdbc;
    this.pager = pager;
  }

  @Override
  public void insert(CardRow c) {
    TenantScope.require();
    jdbc.sql("INSERT INTO portfolio.user_cards (" + COLS + ") VALUES (:id, :a, :p, :nick, :last4, :net, :sd, :due,"
            + " :limit, :opened, :status, :created, :updated, :removed, :v)")
        .params(params(c)).update();
  }

  @Override
  public Optional<CardRow> find(UUID id) {
    TenantScope.require();
    return jdbc.sql("SELECT " + COLS + " FROM portfolio.user_cards WHERE id = :id AND status <> 'removed'")
        .param("id", id).query(CARD).optional();
  }

  @Override
  public Optional<CardRow> lock(UUID id) {
    TenantScope.require();
    return jdbc.sql("SELECT " + COLS + " FROM portfolio.user_cards WHERE id = :id FOR UPDATE").param("id", id)
        .query(CARD).optional();
  }

  @Override
  public void update(CardRow c) {
    TenantScope.require();
    jdbc.sql("UPDATE portfolio.user_cards SET nickname = :nick, statement_day = :sd, due_offset_days = :due,"
            + " credit_limit_paise = :limit, opened_on = :opened, status = :status, updated_at = :updated,"
            + " removed_at = :removed, version = :v WHERE id = :id")
        .params(params(c)).update();
  }

  @Override
  public Page<CardRow> list(PageRequest page) {
    TenantScope.require();
    return pager.page(jdbc, LIST, page, Map.of(), Map.of());
  }

  @Override
  public boolean exists(UUID cardProductId, String last4, UUID exceptId) {
    TenantScope.require();
    return jdbc.sql("SELECT count(1) FROM portfolio.user_cards WHERE card_product_id = :p AND last4 = :l"
            + " AND status <> 'removed' AND id IS DISTINCT FROM :except")
        .param("p", cardProductId).param("l", last4).param("except", exceptId).query(Long.class).single() > 0;
  }

  @Override
  public Optional<Product> product(UUID cardProductId) {
    TenantScope.require();
    return jdbc.sql("SELECT card_product_id, slug, name, issuer_code, network, tier, annual_fee_paise, status,"
            + " current_version, snapshot::text AS snapshot FROM portfolio.catalog_products_proj"
            + " WHERE card_product_id = :id")
        .param("id", cardProductId).query(PRODUCT).optional();
  }

  @Override
  public boolean upsertProduct(Product p, long version) {
    TenantScope.require();
    Map<String, Object> m = new java.util.HashMap<>();
    m.put("id", p.id());
    m.put("slug", p.slug());
    m.put("name", p.name());
    m.put("issuer", p.issuerCode());
    m.put("network", p.network());
    m.put("tier", p.tier());
    m.put("fee", p.annualFeePaise());
    m.put("status", p.status());
    m.put("cv", p.currentVersion());
    m.put("snap", p.snapshotJson());
    return ProjectionVersionGuard.apply(jdbc, UPSERT_PRODUCT, m, version);
  }

  @Override
  public long projectedVersion(UUID cardProductId) {
    TenantScope.require();
    return jdbc.sql("SELECT projected_version FROM portfolio.catalog_products_proj WHERE card_product_id = :id")
        .param("id", cardProductId).query(Long.class).optional().orElse(-1L);
  }

  private static Map<String, Object> params(CardRow c) {
    Map<String, Object> m = new java.util.HashMap<>();
    m.put("id", c.id());
    m.put("a", c.accountId());
    m.put("p", c.cardProductId());
    m.put("nick", c.nickname());
    m.put("last4", c.last4());
    m.put("net", c.network());
    m.put("sd", c.statementDay());
    m.put("due", c.dueOffsetDays());
    m.put("limit", c.creditLimitPaise());
    m.put("opened", c.openedOn());
    m.put("status", c.status());
    m.put("created", Sql.ts(c.createdAt()));
    m.put("updated", Sql.ts(c.updatedAt()));
    m.put("removed", c.removedAt() == null ? null : Sql.ts(c.removedAt()));
    m.put("v", c.version());
    return m;
  }
}
