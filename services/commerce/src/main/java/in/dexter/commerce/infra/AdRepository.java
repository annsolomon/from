package in.dexter.commerce.infra;

import in.dexter.commerce.app.AdStore;
import in.dexter.db.page.KeyType;
import in.dexter.db.page.KeysetPager;
import in.dexter.db.page.KeysetQuery;
import in.dexter.db.page.Page;
import in.dexter.db.page.PageRequest;
import in.dexter.db.sql.Rows;
import in.dexter.db.sql.Sql;
import in.dexter.db.tenant.TenantScope;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Campaigns, creatives, placements and campaign stats on JdbcClient. One instance per pool, like the others. */
public final class AdRepository implements AdStore {
  static final String CAMPAIGN_COLS = "c.id, c.name, c.advertiser, c.status, lower(c.valid) AS valid_from,"
      + " upper(c.valid) AS valid_to, c.weight, c.frequency_cap, c.frequency_window_seconds, c.placements,"
      + " c.version, c.created_at";
  static final String CREATIVE_COLS = "k.id AS k_id, k.campaign_id AS k_campaign, k.headline, k.body, k.image_url,"
      + " k.click_url, k.weight AS k_weight, k.status AS k_status, k.version AS k_version";

  static final RowMapper<Campaign> CAMPAIGN = (rs, n) -> campaign(rs);
  static final RowMapper<Creative> CREATIVE = (rs, n) -> creative(rs);

  /** Operators' campaign list, newest first, on campaigns_admin. */
  static final KeysetQuery<Campaign> CAMPAIGNS = KeysetQuery.<Campaign>builder("created_desc")
      .direction(KeysetQuery.Direction.DESC)
      .key("created_at", KeyType.TIMESTAMPTZ)
      .key("id", KeyType.UUID)
      .sql("SELECT " + CAMPAIGN_COLS + " FROM commerce.campaigns c WHERE (SELECT commerce.is_operator())")
      .mapper(CAMPAIGN)
      .keysOf(c -> List.of(c.createdAt(), c.id()))
      .build();

  private final JdbcClient jdbc;
  private final KeysetPager pager;

  public AdRepository(JdbcClient jdbc, KeysetPager pager) {
    this.jdbc = jdbc;
    this.pager = pager;
  }

  @Override
  public Set<String> placements() {
    TenantScope.require();
    return new HashSet<>(jdbc.sql("SELECT code FROM commerce.placements").query(String.class).list());
  }

  @Override
  public List<Live> live(String placement, Instant now) {
    TenantScope.require();
    return jdbc.sql("SELECT " + CAMPAIGN_COLS + ", " + CREATIVE_COLS + " FROM commerce.campaigns c"
            + " JOIN commerce.creatives k ON k.campaign_id = c.id AND k.status = 'active'"
            + " WHERE c.status = 'active' AND c.placements @> ARRAY[CAST(:p AS text)]"
            + " AND c.valid @> CAST(:now AS timestamptz) ORDER BY c.id, k.id")
        .param("p", placement).param("now", Sql.ts(now))
        .query((rs, n) -> new Live(campaign(rs), creative(rs))).list();
  }

  @Override
  public Optional<Live> creative(UUID creativeId) {
    TenantScope.require();
    return jdbc.sql("SELECT " + CAMPAIGN_COLS + ", " + CREATIVE_COLS + " FROM commerce.creatives k"
            + " JOIN commerce.campaigns c ON c.id = k.campaign_id WHERE k.id = :k")
        .param("k", creativeId).query((rs, n) -> new Live(campaign(rs), creative(rs))).optional();
  }

  @Override
  public void insertCampaign(Campaign c, @Nullable UUID createdBy) {
    TenantScope.require();
    jdbc.sql("INSERT INTO commerce.campaigns (id, name, advertiser, status, valid, weight, frequency_cap,"
            + " frequency_window_seconds, placements, version, created_by, created_at, updated_at) VALUES (:id, :n,"
            + " :adv, :st, tstzrange(CAST(:from AS timestamptz), CAST(:to AS timestamptz), '[)'), :w, :cap, :win,"
            + " :pl, :v, :by, :at, :at)")
        .param("id", c.id()).param("n", c.name()).param("adv", c.advertiser()).param("st", c.status())
        .param("from", Sql.ts(c.validFrom())).param("to", c.validTo() == null ? null : Sql.ts(c.validTo()))
        .param("w", c.weight()).param("cap", c.frequencyCap()).param("win", c.frequencyWindowSeconds())
        .param("pl", Sql.texts(c.placements())).param("v", c.version()).param("by", createdBy)
        .param("at", Sql.ts(c.createdAt())).update();
  }

  @Override
  public boolean updateCampaign(Campaign c, int expectedVersion, Instant at) {
    TenantScope.require();
    return jdbc.sql("UPDATE commerce.campaigns SET name = :n, advertiser = :adv, status = :st,"
            + " valid = tstzrange(CAST(:from AS timestamptz), CAST(:to AS timestamptz), '[)'), weight = :w,"
            + " frequency_cap = :cap, frequency_window_seconds = :win, placements = :pl, version = version + 1,"
            + " updated_at = :at WHERE id = :id AND version = :v")
        .param("n", c.name()).param("adv", c.advertiser()).param("st", c.status())
        .param("from", Sql.ts(c.validFrom())).param("to", c.validTo() == null ? null : Sql.ts(c.validTo()))
        .param("w", c.weight()).param("cap", c.frequencyCap()).param("win", c.frequencyWindowSeconds())
        .param("pl", Sql.texts(c.placements())).param("at", Sql.ts(at)).param("id", c.id())
        .param("v", expectedVersion).update() == 1;
  }

  @Override
  public Optional<Campaign> campaign(UUID id) {
    TenantScope.require();
    return jdbc.sql("SELECT " + CAMPAIGN_COLS + " FROM commerce.campaigns c WHERE c.id = :id").param("id", id)
        .query(CAMPAIGN).optional();
  }

  @Override
  public Page<Campaign> campaigns(PageRequest page) {
    TenantScope.require();
    return pager.page(jdbc, CAMPAIGNS, page, Map.of(), Map.of());
  }

  @Override
  public void insertCreative(Creative k) {
    TenantScope.require();
    jdbc.sql("INSERT INTO commerce.creatives (id, campaign_id, headline, body, image_url, click_url, weight, status,"
            + " version) VALUES (:id, :c, :h, :b, :img, :url, :w, :st, :v)")
        .param("id", k.id()).param("c", k.campaignId()).param("h", k.headline()).param("b", k.body())
        .param("img", k.imageUrl()).param("url", k.clickUrl()).param("w", k.weight()).param("st", k.status())
        .param("v", k.version()).update();
  }

  @Override
  public boolean updateCreative(UUID id, UUID campaignId, String status, int weight, int expectedVersion,
      Instant at) {
    TenantScope.require();
    return jdbc.sql("UPDATE commerce.creatives SET status = :st, weight = :w, version = version + 1,"
            + " updated_at = :at WHERE id = :id AND campaign_id = :c AND version = :v")
        .param("st", status).param("w", weight).param("at", Sql.ts(at)).param("id", id).param("c", campaignId)
        .param("v", expectedVersion).update() == 1;
  }

  @Override
  public Optional<Creative> findCreative(UUID campaignId, UUID creativeId) {
    TenantScope.require();
    return jdbc.sql("SELECT " + CREATIVE_COLS + " FROM commerce.creatives k WHERE k.id = :id AND k.campaign_id = :c")
        .param("id", creativeId).param("c", campaignId).query(CREATIVE).optional();
  }

  @Override
  public List<Creative> creatives(UUID campaignId) {
    TenantScope.require();
    return jdbc.sql("SELECT " + CREATIVE_COLS + " FROM commerce.creatives k WHERE k.campaign_id = :c"
            + " ORDER BY k.created_at, k.id")
        .param("c", campaignId).query(CREATIVE).list();
  }

  @Override
  public List<CampaignStat> campaignStats(LocalDate from, LocalDate to) {
    TenantScope.require();
    return jdbc.sql("SELECT k.campaign_id, c.name, sum(a.impressions) AS impressions, sum(a.clicks) AS clicks"
            + " FROM commerce.ad_counters a JOIN commerce.creatives k ON k.id = a.creative_id"
            + " JOIN commerce.campaigns c ON c.id = k.campaign_id WHERE a.day BETWEEN :f AND :t"
            + " GROUP BY k.campaign_id, c.name ORDER BY impressions DESC, k.campaign_id")
        .param("f", from).param("t", to)
        .query((rs, n) -> new CampaignStat(Rows.uuid(rs, "campaign_id"), rs.getString("name"),
            rs.getLong("impressions"), rs.getLong("clicks")))
        .list();
  }

  private static Campaign campaign(ResultSet rs) throws SQLException {
    String[] placements = Rows.textArray(rs, "placements");
    return new Campaign(Rows.uuid(rs, "id"), rs.getString("name"), rs.getString("advertiser"), rs.getString("status"),
        Rows.instant(rs, "valid_from"), Rows.instant(rs, "valid_to"), rs.getInt("weight"), rs.getInt("frequency_cap"),
        rs.getInt("frequency_window_seconds"), placements == null ? List.of() : Arrays.asList(placements),
        rs.getInt("version"), Rows.instant(rs, "created_at"));
  }

  private static Creative creative(ResultSet rs) throws SQLException {
    return new Creative(Rows.uuid(rs, "k_id"), Rows.uuid(rs, "k_campaign"), rs.getString("headline"),
        rs.getString("body"), rs.getString("image_url"), rs.getString("click_url"), rs.getInt("k_weight"),
        rs.getString("k_status"), rs.getInt("k_version"));
  }
}
