package in.dexter.commerce.infra;

import in.dexter.commerce.app.ApplicationStore;
import in.dexter.commerce.app.CommerceViews.Component;
import in.dexter.commerce.app.DealStore;
import in.dexter.commerce.app.ProjectionStore;
import in.dexter.commerce.app.TrackingStore;
import in.dexter.commerce.domain.ApplicationStatus;
import in.dexter.commerce.domain.ConversionStatus;
import in.dexter.commerce.domain.SavingComponent;
import in.dexter.db.page.KeyType;
import in.dexter.db.page.KeysetPager;
import in.dexter.db.page.KeysetQuery;
import in.dexter.db.page.Page;
import in.dexter.db.page.PageRequest;
import in.dexter.db.sql.Rows;
import in.dexter.db.sql.Sql;
import in.dexter.db.tenant.TenantScope;
import in.dexter.events.ProjectionVersionGuard;
import java.sql.Array;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Deals, tracking, applications and projections on JdbcClient (MASTER_PLAN 2.5). One instance per pool: the app
 * pool's sees what RLS gives the caller's actor, the admin pool's what it gives operators — the SQL is the same.
 */
public final class CommerceRepository implements DealStore, TrackingStore, ApplicationStore, ProjectionStore {
  private static final TypeReference<List<Component>> COMPONENTS = new TypeReference<>() {};
  private static final TypeReference<List<HistoryEntry>> HISTORY = new TypeReference<>() {};

  static final String DEAL_COLS = "id, merchant_id, merchant_name, title, description, category_path::text AS category,"
      + " components::text AS components, eligible_card_products, reference_spend_paise, lower(valid) AS valid_from,"
      + " upper(valid) AS valid_to, status, priority, sponsored, sponsor_weight, network, landing_url,"
      + " commission_bps, version, created_at";
  static final String APP_COLS = "id, account_id, card_product_id, status, status_history::text AS history, reason,"
      + " version, created_at, updated_at, decided_at, decided_by";

  private final JdbcClient jdbc;
  private final KeysetPager pager;
  private final JsonMapper mapper;
  private final RowMapper<Deal> deal;
  private final RowMapper<Application> application;

  /** Operators' deal list, newest first, on deals_admin (created_at DESC, id DESC). */
  final KeysetQuery<Deal> adminDeals;
  /** The caller's applications, newest first, on card_applications_list. Public for the pagination contract IT. */
  public final KeysetQuery<Application> applicationList;
  /** Operators' queue of open applications, oldest first, on card_applications_queue. */
  final KeysetQuery<Application> applicationQueue;

  public CommerceRepository(JdbcClient jdbc, KeysetPager pager, JsonMapper mapper) {
    this.jdbc = jdbc;
    this.pager = pager;
    this.mapper = mapper;
    this.deal = (rs, n) -> new Deal(Rows.uuid(rs, "id"), Rows.uuid(rs, "merchant_id"), rs.getString("merchant_name"),
        rs.getString("title"), rs.getString("description"), rs.getString("category"),
        mapper.readValue(rs.getString("components"), COMPONENTS).stream().map(Component::toDomain).toList(),
        uuids(rs.getArray("eligible_card_products")), rs.getLong("reference_spend_paise"),
        Rows.instant(rs, "valid_from"), Rows.instant(rs, "valid_to"), rs.getString("status"), rs.getInt("priority"),
        rs.getBoolean("sponsored"), rs.getInt("sponsor_weight"), rs.getString("network"),
        rs.getString("landing_url"), rs.getInt("commission_bps"), rs.getInt("version"),
        Rows.instant(rs, "created_at"));
    this.application = (rs, n) -> new Application(Rows.uuid(rs, "id"), Rows.uuid(rs, "account_id"),
        Rows.uuid(rs, "card_product_id"), ApplicationStatus.ofDb(rs.getString("status")),
        mapper.readValue(rs.getString("history"), HISTORY), rs.getString("reason"), rs.getInt("version"),
        Rows.instant(rs, "created_at"), Rows.instant(rs, "updated_at"), Rows.instant(rs, "decided_at"),
        Rows.uuid(rs, "decided_by"));
    this.adminDeals = KeysetQuery.<Deal>builder("created_desc")
        .direction(KeysetQuery.Direction.DESC)
        .key("created_at", KeyType.TIMESTAMPTZ)
        .key("id", KeyType.UUID)
        .sql("SELECT " + DEAL_COLS + " FROM commerce.deals WHERE (SELECT commerce.is_operator())")
        .mapper(deal)
        .keysOf(d -> List.of(d.createdAt(), d.id()))
        .build();
    this.applicationList = KeysetQuery.<Application>builder("created_desc")
        .direction(KeysetQuery.Direction.DESC)
        .key("created_at", KeyType.TIMESTAMPTZ)
        .key("id", KeyType.UUID)
        .sql("SELECT " + APP_COLS + " FROM commerce.card_applications"
            + " WHERE account_id = (SELECT commerce.app_account())")
        .mapper(application)
        .keysOf(a -> List.of(a.createdAt(), a.id()))
        .build();
    this.applicationQueue = KeysetQuery.<Application>builder("queue_asc")
        .direction(KeysetQuery.Direction.ASC)
        .key("created_at", KeyType.TIMESTAMPTZ)
        .key("id", KeyType.UUID)
        .sql("SELECT " + APP_COLS + " FROM commerce.card_applications"
            + " WHERE status IN ('submitted', 'in_review')")
        .mapper(application)
        .keysOf(a -> List.of(a.createdAt(), a.id()))
        .build();
  }

  // ------------------------------------------------------------------------------------------------ deals

  @Override
  public List<Deal> active(@Nullable String category, Instant now, int max) {
    TenantScope.require();
    String sql = "SELECT " + DEAL_COLS + " FROM commerce.deals WHERE status = 'active'"
        + " AND valid @> CAST(:now AS timestamptz)"
        + (category == null ? "" : " AND category_path <@ CAST(:cat AS public.ltree)")
        + " ORDER BY priority DESC, id LIMIT :max";
    var q = jdbc.sql(sql).param("now", Sql.ts(now)).param("max", max);
    if (category != null) {
      q = q.param("cat", category);
    }
    return q.query(deal).list();
  }

  @Override
  public Optional<Deal> findDeal(UUID id) {
    TenantScope.require();
    return jdbc.sql("SELECT " + DEAL_COLS + " FROM commerce.deals WHERE id = :id").param("id", id).query(deal)
        .optional();
  }

  @Override
  public void insert(Deal d, @Nullable UUID createdBy) {
    TenantScope.require();
    jdbc.sql("INSERT INTO commerce.deals (id, merchant_id, merchant_name, title, description, category_path,"
            + " components, eligible_card_products, reference_spend_paise, valid, status, priority, sponsored,"
            + " sponsor_weight, network, landing_url, commission_bps, version, created_by, created_at, updated_at)"
            + " VALUES (:id, :m, :mn, :t, :d, CAST(:cat AS public.ltree), CAST(:comp AS jsonb), :elig, :ref,"
            + " tstzrange(CAST(:from AS timestamptz), CAST(:to AS timestamptz), '[)'), :st, :pr, :sp, :sw, :net,"
            + " :url, :cb, :v, :by, :at, :at)")
        .param("id", d.id()).param("m", d.merchantId()).param("mn", d.merchantName()).param("t", d.title())
        .param("d", d.description()).param("cat", d.categoryPath()).param("comp", components(d.components()))
        .param("elig", Sql.uuids(d.eligibleCardProducts())).param("ref", d.referenceSpendPaise())
        .param("from", Sql.ts(d.validFrom())).param("to", d.validTo() == null ? null : Sql.ts(d.validTo()))
        .param("st", d.status()).param("pr", d.priority()).param("sp", d.sponsored()).param("sw", d.sponsorWeight())
        .param("net", d.network()).param("url", d.landingUrl()).param("cb", d.commissionBps())
        .param("v", d.version()).param("by", createdBy).param("at", Sql.ts(d.createdAt())).update();
  }

  @Override
  public boolean updateStatus(UUID id, String status, int expectedVersion, Instant at) {
    TenantScope.require();
    return jdbc.sql("UPDATE commerce.deals SET status = :st, version = version + 1, updated_at = :at"
            + " WHERE id = :id AND version = :v")
        .param("st", status).param("at", Sql.ts(at)).param("id", id).param("v", expectedVersion).update() == 1;
  }

  @Override
  public Page<Deal> adminList(PageRequest page) {
    TenantScope.require();
    return pager.page(jdbc, adminDeals, page, Map.of(), Map.of());
  }

  @Override
  public Set<UUID> heldCardProducts() {
    TenantScope.require();
    return new HashSet<>(jdbc.sql("SELECT DISTINCT card_product_id FROM commerce.held_cards_proj"
            + " WHERE account_id = (SELECT commerce.app_account())")
        .query((rs, n) -> Rows.uuid(rs, "card_product_id")).list());
  }

  @Override
  public Map<UUID, String> publishedCardNames(Collection<UUID> ids) {
    TenantScope.require();
    Map<UUID, String> out = new LinkedHashMap<>();
    if (ids.isEmpty()) {
      return out;
    }
    jdbc.sql("SELECT card_product_id, name FROM commerce.card_products_proj"
            + " WHERE card_product_id = ANY(:ids) AND status = 'published'")
        .param("ids", Sql.uuids(ids))
        .query((rs, n) -> Map.entry(Rows.uuid(rs, "card_product_id"), rs.getString("name"))).list()
        .forEach(e -> out.put(e.getKey(), e.getValue()));
    return out;
  }

  // --------------------------------------------------------------------------------------------- tracking

  @Override
  public void insertClick(Click c) {
    TenantScope.require();
    jdbc.sql("INSERT INTO commerce.deal_clicks (id, created_at, account_id, deal_id, click_token_hash, ip_hash,"
            + " ua_hash) VALUES (:id, :at, :a, :d, :t, :ip, :ua)")
        .param("id", c.id()).param("at", Sql.ts(c.at())).param("a", c.accountId()).param("d", c.dealId())
        .param("t", c.tokenHash()).param("ip", c.ipHash()).param("ua", c.uaHash()).update();
  }

  @Override
  public Optional<Click> click(UUID id, Instant at) {
    TenantScope.require();
    // created_at is the id's own UUIDv7 timestamp: an exact primary-key probe in one daily partition
    return jdbc.sql("SELECT id, created_at, account_id, deal_id, click_token_hash, ip_hash, ua_hash"
            + " FROM commerce.deal_clicks WHERE created_at = :at AND id = :id")
        .param("at", Sql.ts(at)).param("id", id)
        .query((rs, n) -> new Click(Rows.uuid(rs, "id"), Rows.instant(rs, "created_at"), Rows.uuid(rs, "account_id"),
            Rows.uuid(rs, "deal_id"), rs.getBytes("click_token_hash"), rs.getBytes("ip_hash"),
            rs.getBytes("ua_hash")))
        .optional();
  }

  @Override
  public void insertImpression(UUID id, Instant at, UUID creativeId, UUID campaignId, String placement,
      @Nullable UUID accountId, byte[] visitorHash) {
    TenantScope.require();
    jdbc.sql("INSERT INTO commerce.ad_impressions_raw (id, created_at, creative_id, campaign_id, placement,"
            + " account_id, visitor_hash) VALUES (:id, :at, :k, :c, :p, :a, :v)")
        .param("id", id).param("at", Sql.ts(at)).param("k", creativeId).param("c", campaignId).param("p", placement)
        .param("a", accountId).param("v", visitorHash).update();
  }

  static final RowMapper<Conversion> CONVERSION = (rs, n) -> new Conversion(Rows.uuid(rs, "id"),
      rs.getString("network"), rs.getString("provider_ref"), Rows.uuid(rs, "click_id"), Rows.uuid(rs, "deal_id"),
      Rows.uuid(rs, "account_id"), rs.getLong("amount_paise"), rs.getLong("commission_paise"),
      ConversionStatus.ofDb(rs.getString("status")), Rows.instant(rs, "occurred_at"), rs.getInt("version"));

  @Override
  public boolean insertConversion(Conversion c) {
    TenantScope.require();
    return jdbc.sql("INSERT INTO commerce.conversions (id, network, provider_ref, click_id, deal_id, account_id,"
            + " amount_paise, commission_paise, status, occurred_at, version) VALUES (:id, :n, :r, :c, :d, :a, :amt,"
            + " :com, :st, :at, 0) ON CONFLICT (network, provider_ref) DO NOTHING")
        .param("id", c.id()).param("n", c.network()).param("r", c.providerRef()).param("c", c.clickId())
        .param("d", c.dealId()).param("a", c.accountId()).param("amt", c.amountPaise())
        .param("com", c.commissionPaise()).param("st", c.status().db()).param("at", Sql.ts(c.occurredAt()))
        .update() == 1;
  }

  @Override
  public Optional<Conversion> lockConversion(String network, String providerRef) {
    TenantScope.require();
    return jdbc.sql("SELECT id, network, provider_ref, click_id, deal_id, account_id, amount_paise, commission_paise,"
            + " status, occurred_at, version FROM commerce.conversions WHERE network = :n AND provider_ref = :r"
            + " FOR UPDATE")
        .param("n", network).param("r", providerRef).query(CONVERSION).optional();
  }

  @Override
  public void updateConversion(UUID id, ConversionStatus status, long amountPaise, long commissionPaise,
      Instant at) {
    TenantScope.require();
    jdbc.sql("UPDATE commerce.conversions SET status = :st, amount_paise = :amt, commission_paise = :com,"
            + " version = version + 1, updated_at = :at WHERE id = :id")
        .param("st", status.db()).param("amt", amountPaise).param("com", commissionPaise).param("at", Sql.ts(at))
        .param("id", id).update();
  }

  @Override
  public void addDealCounters(UUID dealId, LocalDate day, long clicks, long uniques, long conversions,
      long commissionPaise) {
    TenantScope.require();
    jdbc.sql("INSERT INTO commerce.deal_click_counters AS c (deal_id, day, clicks, uniques, conversions,"
            + " commission_paise, updated_at) VALUES (:d, :day, :cl, :u, :cv, :cm, now())"
            + " ON CONFLICT (deal_id, day) DO UPDATE SET clicks = c.clicks + EXCLUDED.clicks,"
            + " uniques = c.uniques + EXCLUDED.uniques, conversions = c.conversions + EXCLUDED.conversions,"
            + " commission_paise = c.commission_paise + EXCLUDED.commission_paise, updated_at = now()")
        .param("d", dealId).param("day", day).param("cl", clicks).param("u", uniques).param("cv", conversions)
        .param("cm", commissionPaise).update();
  }

  @Override
  public void addAdCounters(UUID creativeId, LocalDate day, long impressions, long clicks) {
    TenantScope.require();
    jdbc.sql("INSERT INTO commerce.ad_counters AS c (creative_id, day, impressions, clicks, updated_at)"
            + " VALUES (:k, :day, :i, :cl, now()) ON CONFLICT (creative_id, day) DO UPDATE"
            + " SET impressions = c.impressions + EXCLUDED.impressions, clicks = c.clicks + EXCLUDED.clicks,"
            + " updated_at = now()")
        .param("k", creativeId).param("day", day).param("i", impressions).param("cl", clicks).update();
  }

  @Override
  public List<DealStat> dealStats(LocalDate from, LocalDate to) {
    TenantScope.require();
    return jdbc.sql("SELECT c.deal_id, coalesce(d.title, '') AS title, sum(c.clicks) AS clicks,"
            + " sum(c.uniques) AS uniques, sum(c.conversions) AS conversions, sum(c.commission_paise) AS commission"
            + " FROM commerce.deal_click_counters c LEFT JOIN commerce.deals d ON d.id = c.deal_id"
            + " WHERE c.day BETWEEN :f AND :t GROUP BY c.deal_id, d.title ORDER BY clicks DESC, c.deal_id")
        .param("f", from).param("t", to)
        .query((rs, n) -> new DealStat(Rows.uuid(rs, "deal_id"), rs.getString("title"), rs.getLong("clicks"),
            rs.getLong("uniques"), rs.getLong("conversions"), rs.getLong("commission")))
        .list();
  }

  // --------------------------------------------------------------------------------------------- applications

  @Override
  public boolean insert(Application a) {
    TenantScope.require();
    try {
      jdbc.sql("INSERT INTO commerce.card_applications (id, account_id, card_product_id, status, status_history,"
              + " reason, version, created_at, updated_at) VALUES (:id, :a, :c, :st, CAST(:h AS jsonb), :r, :v,"
              + " :at, :at)")
          .param("id", a.id()).param("a", a.accountId()).param("c", a.cardProductId()).param("st", a.status().db())
          .param("h", mapper.writeValueAsString(a.history())).param("r", a.reason()).param("v", a.version())
          .param("at", Sql.ts(a.createdAt())).update();
      return true;
    } catch (DuplicateKeyException e) {
      return false;
    }
  }

  @Override
  public Optional<Application> find(UUID id) {
    TenantScope.require();
    return jdbc.sql("SELECT " + APP_COLS + " FROM commerce.card_applications WHERE id = :id").param("id", id)
        .query(application).optional();
  }

  @Override
  public Optional<Application> lock(UUID id) {
    TenantScope.require();
    return jdbc.sql("SELECT " + APP_COLS + " FROM commerce.card_applications WHERE id = :id FOR UPDATE")
        .param("id", id).query(application).optional();
  }

  @Override
  public boolean transition(UUID id, int expectedVersion, ApplicationStatus next, HistoryEntry entry,
      @Nullable String reason, @Nullable Instant decidedAt, @Nullable UUID decidedBy) {
    TenantScope.require();
    return jdbc.sql("UPDATE commerce.card_applications SET status = :st,"
            + " status_history = status_history || jsonb_build_array(CAST(:e AS jsonb)), reason = :r,"
            + " decided_at = CAST(:da AS timestamptz), decided_by = CAST(:db AS uuid), version = version + 1,"
            + " updated_at = :at WHERE id = :id AND version = :v")
        .param("st", next.db()).param("e", mapper.writeValueAsString(entry)).param("r", reason)
        .param("da", decidedAt == null ? null : Sql.ts(decidedAt)).param("db", decidedBy)
        .param("at", Sql.ts(entry.at())).param("id", id).param("v", expectedVersion).update() == 1;
  }

  @Override
  public Page<Application> list(PageRequest page) {
    TenantScope.require();
    return pager.page(jdbc, applicationList, page, Map.of(), Map.of());
  }

  @Override
  public Page<Application> queue(PageRequest page) {
    TenantScope.require();
    return pager.page(jdbc, applicationQueue, page, Map.of(), Map.of());
  }

  // --------------------------------------------------------------------------------------------- projections

  static final String UPSERT_CARD = "INSERT INTO commerce.card_products_proj (card_product_id, slug, name,"
      + " issuer_code, network, status, projected_version, updated_at) VALUES (:id, :slug, :name, :iss, :net, :st,"
      + " :version, now()) ON CONFLICT (card_product_id) DO UPDATE SET slug = EXCLUDED.slug, name = EXCLUDED.name,"
      + " issuer_code = EXCLUDED.issuer_code, network = EXCLUDED.network, status = EXCLUDED.status,"
      + " projected_version = EXCLUDED.projected_version, updated_at = now()"
      + " WHERE commerce.card_products_proj.projected_version < EXCLUDED.projected_version";
  static final String UPSERT_HELD = "INSERT INTO commerce.held_cards_proj (account_id, user_card_id,"
      + " card_product_id, projected_version, updated_at) VALUES (:a, :uc, :cp, :version, now())"
      + " ON CONFLICT (account_id, user_card_id) DO UPDATE"
      + " SET card_product_id = EXCLUDED.card_product_id, projected_version = EXCLUDED.projected_version,"
      + "     updated_at = now()"
      + " WHERE commerce.held_cards_proj.projected_version < EXCLUDED.projected_version";
  static final String DELETE_HELD = "DELETE FROM commerce.held_cards_proj WHERE account_id = :a"
      + " AND user_card_id = :uc AND projected_version < :version";
  static final String UPSERT_AD_FREE = "INSERT INTO commerce.ad_free_proj (account_id, tier, ad_free,"
      + " projected_version, updated_at) VALUES (:a, :tier, :af, :version, now())"
      + " ON CONFLICT (account_id) DO UPDATE SET tier = EXCLUDED.tier, ad_free = EXCLUDED.ad_free,"
      + " projected_version = EXCLUDED.projected_version, updated_at = now()"
      + " WHERE commerce.ad_free_proj.projected_version < EXCLUDED.projected_version";

  @Override
  public boolean upsertCardProduct(UUID cardProductId, String slug, String name, String issuerCode, String network,
      String status, long version) {
    TenantScope.require();
    return ProjectionVersionGuard.apply(jdbc, UPSERT_CARD, Map.of("id", cardProductId, "slug", slug, "name", name,
        "iss", issuerCode, "net", network, "st", status), version);
  }

  @Override
  public boolean upsertHeldCard(UUID accountId, UUID userCardId, UUID cardProductId, long version) {
    TenantScope.require();
    return ProjectionVersionGuard.apply(jdbc, UPSERT_HELD, Map.of("a", accountId, "uc", userCardId,
        "cp", cardProductId), version);
  }

  @Override
  public boolean deleteHeldCard(UUID accountId, UUID userCardId, long version) {
    TenantScope.require();
    if (ProjectionVersionGuard.apply(jdbc, DELETE_HELD, Map.of("a", accountId, "uc", userCardId), version)) {
      return true;
    }
    // nothing deleted: already gone (a replayed remove) or a newer version is stored
    return jdbc.sql("SELECT count(1) FROM commerce.held_cards_proj WHERE account_id = :a AND user_card_id = :uc")
        .param("a", accountId).param("uc", userCardId).query(Long.class).single() == 0;
  }

  @Override
  public boolean upsertAdFree(UUID accountId, String tier, boolean adFree, long version) {
    TenantScope.require();
    return ProjectionVersionGuard.apply(jdbc, UPSERT_AD_FREE, Map.of("a", accountId, "tier", tier, "af", adFree),
        version);
  }

  @Override
  public boolean adFree() {
    TenantScope.require();
    return jdbc.sql("SELECT ad_free FROM commerce.ad_free_proj WHERE account_id = (SELECT commerce.app_account())")
        .query(Boolean.class).optional().orElse(false);
  }

  // --------------------------------------------------------------------------------------------- helpers

  private String components(List<SavingComponent> components) {
    return mapper.writeValueAsString(components.stream().map(Component::of).toList());
  }

  static List<UUID> uuids(@Nullable Array a) throws SQLException {
    if (a == null) {
      return List.of();
    }
    Object[] values = (Object[]) a.getArray();
    List<UUID> out = new ArrayList<>(values.length);
    for (Object v : values) {
      out.add(v instanceof UUID u ? u : UUID.fromString(String.valueOf(v)));
    }
    return out;
  }
}
