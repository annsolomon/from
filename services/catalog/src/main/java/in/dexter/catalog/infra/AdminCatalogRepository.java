package in.dexter.catalog.infra;

import in.dexter.catalog.app.AdminCatalogStore;
import in.dexter.catalog.domain.CardDocument;
import in.dexter.db.page.KeyType;
import in.dexter.db.page.KeysetPager;
import in.dexter.db.page.KeysetQuery;
import in.dexter.db.page.Page;
import in.dexter.db.page.PageRequest;
import in.dexter.db.sql.Rows;
import in.dexter.db.sql.Sql;
import in.dexter.db.tenant.TenantScope;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

/** Catalog writes and editor reads on the catalog_admin pool (RLS: CATALOG_EDITOR / SUPER_ADMIN actor roles). */
public final class AdminCatalogRepository implements AdminCatalogStore {
  static final String CARD_COLS = "id, issuer_id, slug, name, network, status, current_version, content_hash,"
      + " updated_at, version";
  static final RowMapper<Card> CARD = (rs, i) -> new Card(Rows.uuid(rs, "id"), Rows.uuid(rs, "issuer_id"),
      rs.getString("slug"), rs.getString("name"), rs.getString("network"), rs.getString("status"),
      Rows.nullableInt(rs, "current_version"), rs.getBytes("content_hash"), Rows.instant(rs, "updated_at"),
      rs.getLong("version"));
  static final RowMapper<Version> VERSION = (rs, i) -> new Version(Rows.uuid(rs, "card_product_id"),
      rs.getInt("version"), rs.getString("snapshot"), Rows.instant(rs, "effective_from"),
      Rows.instant(rs, "effective_to"));
  static final String OFFER_COLS = "id, issuer_id, card_product_id, merchant_id, category_path::text AS category_path,"
      + " title, kind, value_bps, value_paise, max_discount_paise, min_spend_paise, lower(valid) AS valid_from,"
      + " upper(valid) AS valid_to, stackable, terms_url, status, version";
  static final RowMapper<OfferRow> OFFER = (rs, i) -> new OfferRow(Rows.uuid(rs, "id"), Rows.uuid(rs, "issuer_id"),
      Rows.uuid(rs, "card_product_id"), Rows.uuid(rs, "merchant_id"), rs.getString("category_path"),
      rs.getString("title"), rs.getString("kind"), Rows.nullableInt(rs, "value_bps"), Rows.nullableLong(rs, "value_paise"),
      Rows.nullableLong(rs, "max_discount_paise"), rs.getLong("min_spend_paise"), Rows.instant(rs, "valid_from"),
      Rows.instant(rs, "valid_to"), rs.getBoolean("stackable"), rs.getString("terms_url"), rs.getString("status"),
      rs.getInt("version"));
  static KeysetQuery<Card> list(boolean byStatus) {
    return KeysetQuery.<Card>builder("name_asc")
        .direction(KeysetQuery.Direction.ASC)
        .key("name", KeyType.TEXT)
        .key("id", KeyType.UUID)
        .sql("SELECT " + CARD_COLS + " FROM catalog.card_products WHERE (SELECT catalog.is_editor())"
            + (byStatus ? " AND status = :status" : ""))
        .mapper(CARD)
        .keysOf(c -> List.of(c.name(), c.id()))
        .build();
  }

  static final KeysetQuery<Card> LIST_ALL = list(false);
  static final KeysetQuery<Card> LIST_STATUS = list(true);

  private final JdbcClient jdbc;
  private final KeysetPager pager;
  private final ObjectMapper mapper;

  public AdminCatalogRepository(JdbcClient jdbc, KeysetPager pager, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.pager = pager;
    this.mapper = mapper;
  }

  // ------------------------------------------------------------------------------------------- issuers

  @Override
  public Optional<Issuer> issuerByCode(String code) {
    TenantScope.require();
    return jdbc.sql("SELECT id, code, name, type FROM catalog.issuers WHERE code = :c").param("c", code)
        .query((rs, i) -> new Issuer(Rows.uuid(rs, "id"), rs.getString("code"), rs.getString("name"),
            rs.getString("type")))
        .optional();
  }

  @Override
  public Optional<Issuer> issuer(UUID id) {
    TenantScope.require();
    return jdbc.sql("SELECT id, code, name, type FROM catalog.issuers WHERE id = :id").param("id", id)
        .query((rs, i) -> new Issuer(Rows.uuid(rs, "id"), rs.getString("code"), rs.getString("name"),
            rs.getString("type")))
        .optional();
  }

  @Override
  public void insertIssuer(Issuer i) {
    TenantScope.require();
    jdbc.sql("INSERT INTO catalog.issuers (id, code, name, type) VALUES (:id, :c, :n, :t)")
        .param("id", i.id()).param("c", i.code()).param("n", i.name()).param("t", i.type()).update();
  }

  // ------------------------------------------------------------------------------------------- cards

  @Override
  public Page<Card> list(PageRequest page, String status) {
    TenantScope.require();
    Map<String, Object> p = new HashMap<>();
    Map<String, String> f = new HashMap<>();
    if (status != null) {
      p.put("status", status);
      f.put("status", status);
    }
    return pager.page(jdbc, status == null ? LIST_ALL : LIST_STATUS, page, p, f);
  }

  @Override
  public Optional<Card> find(UUID id) {
    TenantScope.require();
    return jdbc.sql("SELECT " + CARD_COLS + " FROM catalog.card_products WHERE id = :id").param("id", id).query(CARD)
        .optional();
  }

  @Override
  public Optional<Card> lock(UUID id) {
    TenantScope.require();
    return jdbc.sql("SELECT " + CARD_COLS + " FROM catalog.card_products WHERE id = :id FOR UPDATE").param("id", id)
        .query(CARD).optional();
  }

  @Override
  public boolean slugTaken(String slug) {
    TenantScope.require();
    return jdbc.sql("SELECT count(1) FROM catalog.card_products WHERE slug = :s").param("s", slug)
        .query(Long.class).single() > 0;
  }

  @Override
  public void insertCard(UUID id, UUID issuerId, CardDocument d, Instant now) {
    TenantScope.require();
    jdbc.sql("INSERT INTO catalog.card_products (id, issuer_id, slug, name, network, tier, joining_fee_paise,"
            + " annual_fee_paise, fee_waiver_spend_paise, apr_bps, forex_markup_bps, fuel_surcharge_waiver_bps, features,"
            + " status, created_at, updated_at) VALUES (:id, :iss, :slug, :name, :net, :tier, :jf, :af, :fw, :apr, :fx,"
            + " :fuel, CAST(:features AS jsonb), 'draft', :now, :now)")
        .param("id", id).param("iss", issuerId).param("slug", d.slug()).param("name", d.name())
        .param("net", d.network()).param("tier", d.tier()).param("jf", d.joiningFeePaise())
        .param("af", d.annualFeePaise()).param("fw", d.feeWaiverSpendPaise()).param("apr", d.aprBps())
        .param("fx", d.forexMarkupBps()).param("fuel", d.fuelSurchargeWaiverBps())
        .param("features", mapper.writeValueAsString(d.features())).param("now", Sql.ts(now))
        .update();
  }

  @Override
  public void markPublished(UUID id, UUID issuerId, CardDocument d, int currentVersion, byte[] hash, Instant now) {
    TenantScope.require();
    jdbc.sql("UPDATE catalog.card_products SET issuer_id = :iss, name = :name, network = :net, tier = :tier,"
            + " joining_fee_paise = :jf, annual_fee_paise = :af, fee_waiver_spend_paise = :fw, apr_bps = :apr,"
            + " forex_markup_bps = :fx, fuel_surcharge_waiver_bps = :fuel, features = CAST(:features AS jsonb),"
            + " status = 'published', current_version = :cv, content_hash = :hash, updated_at = :now,"
            + " version = version + 1 WHERE id = :id")
        .param("iss", issuerId).param("name", d.name()).param("net", d.network()).param("tier", d.tier())
        .param("jf", d.joiningFeePaise()).param("af", d.annualFeePaise()).param("fw", d.feeWaiverSpendPaise())
        .param("apr", d.aprBps()).param("fx", d.forexMarkupBps()).param("fuel", d.fuelSurchargeWaiverBps())
        .param("features", mapper.writeValueAsString(d.features())).param("cv", currentVersion).param("hash", hash)
        .param("now", Sql.ts(now)).param("id", id)
        .update();
  }

  @Override
  public void markRetired(UUID id, Instant now) {
    TenantScope.require();
    jdbc.sql("UPDATE catalog.card_products SET status = 'retired', updated_at = :now, version = version + 1"
        + " WHERE id = :id").param("now", Sql.ts(now)).param("id", id).update();
  }

  // ------------------------------------------------------------------------------------------- drafts

  @Override
  public Optional<Draft> draft(UUID cardId) {
    TenantScope.require();
    return jdbc.sql("SELECT card_product_id, document::text AS document, updated_by, updated_at, version"
            + " FROM catalog.card_product_drafts WHERE card_product_id = :id")
        .param("id", cardId)
        .query((rs, i) -> new Draft(Rows.uuid(rs, "card_product_id"), rs.getString("document"),
            Rows.uuid(rs, "updated_by"), Rows.instant(rs, "updated_at"), rs.getLong("version")))
        .optional();
  }

  @Override
  public void deleteDraft(UUID cardId) {
    TenantScope.require();
    jdbc.sql("DELETE FROM catalog.card_product_drafts WHERE card_product_id = :id").param("id", cardId).update();
  }

  @Override
  public boolean saveDraft(UUID cardId, String json, UUID adminId, Long expectedVersion, Instant now) {
    TenantScope.require();
    if (expectedVersion == null) {
      return jdbc.sql("INSERT INTO catalog.card_product_drafts (card_product_id, document, updated_by, updated_at)"
              + " VALUES (:id, CAST(:doc AS jsonb), :by, :now) ON CONFLICT (card_product_id) DO NOTHING")
          .param("id", cardId).param("doc", json).param("by", adminId).param("now", Sql.ts(now)).update() == 1;
    }
    return jdbc.sql("UPDATE catalog.card_product_drafts SET document = CAST(:doc AS jsonb), updated_by = :by,"
            + " updated_at = :now, version = version + 1 WHERE card_product_id = :id AND version = :v")
        .param("doc", json).param("by", adminId).param("now", Sql.ts(now)).param("id", cardId)
        .param("v", expectedVersion).update() == 1;
  }

  // ------------------------------------------------------------------------------------------- versions + rules

  @Override
  public Optional<Version> currentVersion(UUID cardId) {
    TenantScope.require();
    return jdbc.sql("SELECT card_product_id, version, snapshot::text AS snapshot, effective_from, effective_to"
            + " FROM catalog.card_product_versions WHERE card_product_id = :id ORDER BY version DESC LIMIT 1")
        .param("id", cardId).query(VERSION).optional();
  }

  @Override
  public List<Version> versions(UUID cardId) {
    TenantScope.require();
    return jdbc.sql("SELECT card_product_id, version, snapshot::text AS snapshot, effective_from, effective_to"
            + " FROM catalog.card_product_versions WHERE card_product_id = :id ORDER BY version DESC")
        .param("id", cardId).query(VERSION).list();
  }

  @Override
  public void closeCurrent(UUID cardId, Instant at) {
    TenantScope.require();
    jdbc.sql("UPDATE catalog.card_product_versions SET effective_to = :at WHERE card_product_id = :id"
        + " AND effective_to IS NULL").param("at", Sql.ts(at)).param("id", cardId).update();
    jdbc.sql("UPDATE catalog.reward_rules SET valid = tstzrange(lower(valid), CAST(:at AS timestamptz), '[)')"
        + " WHERE card_product_id = :id AND upper_inf(valid)").param("at", Sql.ts(at)).param("id", cardId).update();
  }

  @Override
  public List<RuleRow> rules(UUID cardId, int version) {
    TenantScope.require();
    return jdbc.sql("SELECT id, card_product_id, version, category_path::text AS category_path, merchant_id,"
            + " reward_type, rate_bps, point_value_milli_paise, cap_paise, cap_period, min_txn_paise,"
            + " excluded_paths::text[] AS excluded_paths, lower(valid) AS valid_from, priority"
            + " FROM catalog.reward_rules WHERE card_product_id = :id AND version = :v ORDER BY priority, id")
        .param("id", cardId).param("v", version)
        .query((rs, i) -> {
          String[] ex = Rows.textArray(rs, "excluded_paths");
          return new RuleRow(Rows.uuid(rs, "id"), Rows.uuid(rs, "card_product_id"), rs.getInt("version"),
              new CardDocument.Rule(rs.getString("category_path"), Rows.uuid(rs, "merchant_id"), null,
                  rs.getString("reward_type"), rs.getInt("rate_bps"), rs.getLong("point_value_milli_paise"),
                  Rows.nullableLong(rs, "cap_paise"), rs.getString("cap_period"), rs.getLong("min_txn_paise"),
                  ex == null ? List.of() : Arrays.asList(ex), rs.getInt("priority")),
              Rows.instant(rs, "valid_from"));
        })
        .list();
  }

  @Override
  public void insertVersion(UUID cardId, int version, String snapshotJson, Instant from) {
    TenantScope.require();
    jdbc.sql("INSERT INTO catalog.card_product_versions (card_product_id, version, snapshot, effective_from)"
            + " VALUES (:id, :v, CAST(:s AS jsonb), :f)")
        .param("id", cardId).param("v", version).param("s", snapshotJson).param("f", Sql.ts(from)).update();
  }

  @Override
  public void insertRules(List<RuleRow> rules) {
    TenantScope.require();
    for (RuleRow row : rules) {
      CardDocument.Rule r = row.rule();
      jdbc.sql("INSERT INTO catalog.reward_rules (id, card_product_id, version, category_path, merchant_id,"
              + " reward_type, rate_bps, point_value_milli_paise, cap_paise, cap_period, min_txn_paise, excluded_paths,"
              + " valid, priority) VALUES (:id, :card, :v, CAST(:path AS public.ltree), :m, :t, :rate, :pv, :cap, :cp,"
              + " :min, CAST(:ex AS public.ltree[]), tstzrange(CAST(:from AS timestamptz), NULL, '[)'), :prio)")
          .param("id", row.id()).param("card", row.cardId()).param("v", row.version())
          .param("path", r.categoryPath()).param("m", r.merchantId()).param("t", r.rewardType())
          .param("rate", r.rateBps()).param("pv", r.pointValueMilliPaise()).param("cap", r.capPaise())
          .param("cp", r.capPeriod()).param("min", r.minTxnPaise())
          .param("ex", r.excludedPaths() == null || r.excludedPaths().isEmpty() ? null : Sql.texts(r.excludedPaths()))
          .param("from", Sql.ts(row.validFrom())).param("prio", r.priority())
          .update();
    }
  }

  @Override
  public void replaceMilestone(UUID cardId, CardDocument.Milestone m) {
    TenantScope.require();
    jdbc.sql("DELETE FROM catalog.milestones WHERE card_product_id = :id").param("id", cardId).update();
    if (m != null) {
      jdbc.sql("INSERT INTO catalog.milestones (card_product_id, spend_threshold_paise, period, benefit_type,"
              + " benefit_value_paise) VALUES (:id, :s, :p, :t, :v)")
          .param("id", cardId).param("s", m.spendThresholdPaise()).param("p", m.period()).param("t", m.benefitType())
          .param("v", m.benefitValuePaise() == null ? 0 : m.benefitValuePaise())
          .update();
    }
  }

  // ------------------------------------------------------------------------------------------- offers

  @Override
  public void insertOffer(OfferRow o) {
    TenantScope.require();
    jdbc.sql("INSERT INTO catalog.offers (id, issuer_id, card_product_id, merchant_id, category_path, title, kind,"
            + " value_bps, value_paise, max_discount_paise, min_spend_paise, valid, stackable, terms_url, status)"
            + " VALUES (:id, :iss, :card, :m, CAST(:path AS public.ltree), :title, :kind, :bps, :paise, :max, :min,"
            + " tstzrange(CAST(:from AS timestamptz), CAST(:to AS timestamptz), '[)'), :stack, :terms, :status)")
        .param("id", o.id()).param("iss", o.issuerId()).param("card", o.cardProductId()).param("m", o.merchantId())
        .param("path", o.categoryPath()).param("title", o.title()).param("kind", o.kind())
        .param("bps", o.valueBps()).param("paise", o.valuePaise()).param("max", o.maxDiscountPaise())
        .param("min", o.minSpendPaise()).param("from", Sql.ts(o.validFrom()))
        .param("to", o.validTo() == null ? null : Sql.ts(o.validTo())).param("stack", o.stackable())
        .param("terms", o.termsUrl()).param("status", o.status())
        .update();
  }

  @Override
  public Optional<OfferRow> lockOffer(UUID id) {
    TenantScope.require();
    return jdbc.sql("SELECT " + OFFER_COLS + " FROM catalog.offers WHERE id = :id FOR UPDATE").param("id", id)
        .query(OFFER).optional();
  }

  @Override
  public void withdrawOffer(UUID id, Instant now) {
    TenantScope.require();
    jdbc.sql("UPDATE catalog.offers SET status = 'withdrawn', updated_at = :now, version = version + 1 WHERE id = :id")
        .param("now", Sql.ts(now)).param("id", id).update();
  }
}
