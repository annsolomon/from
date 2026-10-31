package in.dexter.reco.infra;

import in.dexter.db.sql.Rows;
import in.dexter.db.sql.Sql;
import in.dexter.db.tenant.TenantScope;
import in.dexter.events.ProjectionVersionGuard;
import in.dexter.reco.app.RecoStore;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Reco's tables (RLS: the tenant's rows, except the catalog projection which the PROJECTOR actor writes and every
 * tenant reads).
 *
 * <p>{@code category_root} and {@code category_path} are {@code ltree}: written through {@link Sql#ltree} and read
 * back with a {@code ::text} cast, as ledger and planning do. {@code excluded_paths} is a {@code text[]}.
 */
public final class RecoRepository implements RecoStore {
  static final RowMapper<MonthRow> MONTH = (rs, n) -> new MonthRow(rs.getString("category_root"),
      Rows.date(rs, "month"), rs.getLong("total_paise"), rs.getInt("txn_count"));
  static final RowMapper<ProfileRow> PROFILE = (rs, n) -> new ProfileRow(rs.getString("category_root"),
      rs.getString("window_code"), rs.getLong("monthly_avg_paise"), rs.getLong("total_paise"),
      rs.getInt("txn_count"));
  static final RowMapper<HeldCard> HELD = (rs, n) -> new HeldCard(Rows.uuid(rs, "user_card_id"),
      Rows.uuid(rs, "card_product_id"), rs.getLong("projected_version"));
  static final String CARD_COLS = "card_product_id, slug, name, issuer_code, network, tier, annual_fee_paise,"
      + " fee_waiver_spend_paise, milestone::text AS milestone, status, current_version, projected_version";
  static final RowMapper<CardRow> CARD = (rs, n) -> new CardRow(Rows.uuid(rs, "card_product_id"),
      rs.getString("slug"), rs.getString("name"), rs.getString("issuer_code"), rs.getString("network"),
      rs.getString("tier"), rs.getLong("annual_fee_paise"), Rows.nullableLong(rs, "fee_waiver_spend_paise"),
      rs.getString("milestone"), rs.getString("status"), rs.getInt("current_version"),
      rs.getLong("projected_version"));
  static final String RULE_COLS = "card_product_id, rule_id, category_path::text AS category_path, merchant_id,"
      + " reward_type, rate_bps, point_value_milli_paise, cap_paise, cap_period, min_txn_paise, excluded_paths,"
      + " priority";
  static final RowMapper<RuleRow> RULE = (rs, n) -> new RuleRow(Rows.uuid(rs, "card_product_id"),
      Rows.uuid(rs, "rule_id"), rs.getString("category_path"), Rows.uuid(rs, "merchant_id"),
      rs.getString("reward_type"), rs.getInt("rate_bps"), rs.getLong("point_value_milli_paise"),
      Rows.nullableLong(rs, "cap_paise"), rs.getString("cap_period"), rs.getLong("min_txn_paise"),
      List.of(Rows.textArray(rs, "excluded_paths")), rs.getInt("priority"));
  static final RowMapper<StateRow> STATE = (rs, n) -> new StateRow(Rows.uuid(rs, "account_id"),
      rs.getLong("ledger_ver"), rs.getLong("portfolio_ver"), rs.getLong("catalog_ver"), rs.getBoolean("dirty"),
      Rows.instant(rs, "dirty_since"), Rows.instant(rs, "computed_at"), rs.getInt("attempts"));
  static final RowMapper<ResultRow> RESULT = (rs, n) -> new ResultRow(Rows.uuid(rs, "account_id"),
      rs.getString("state_hash"), Rows.instant(rs, "computed_at"), rs.getString("payload"));

  static final String UPSERT_CARD = "INSERT INTO reco.catalog_cards_proj (card_product_id, slug, name,"
      + " issuer_code, network, tier, annual_fee_paise, fee_waiver_spend_paise, milestone, status,"
      + " current_version, projected_version, updated_at)"
      + " VALUES (:id, :slug, :name, :issuer, :network, :tier, :fee, :waiver, :milestone, :status, :cv, :version,"
      + " now()) ON CONFLICT (card_product_id) DO UPDATE"
      + " SET slug = EXCLUDED.slug, name = EXCLUDED.name, issuer_code = EXCLUDED.issuer_code,"
      + "     network = EXCLUDED.network, tier = EXCLUDED.tier, annual_fee_paise = EXCLUDED.annual_fee_paise,"
      + "     fee_waiver_spend_paise = EXCLUDED.fee_waiver_spend_paise, milestone = EXCLUDED.milestone,"
      + "     status = EXCLUDED.status, current_version = EXCLUDED.current_version,"
      + "     projected_version = EXCLUDED.projected_version, updated_at = now()"
      + " WHERE reco.catalog_cards_proj.projected_version < EXCLUDED.projected_version";

  private final JdbcClient jdbc;

  public RecoRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  // ---------------------------------------------------------------- spend

  /**
   * The delta upsert. {@code GREATEST(0, ...)} is on <em>both</em> the inserted row and the {@code DO UPDATE},
   * because Postgres checks the proposed INSERT against the CHECK before falling through to {@code ON CONFLICT} —
   * the P07/A-47 lesson, which planning hit again in P13.
   */
  @Override
  public void addSpend(UUID account, String categoryRoot, LocalDate month, long deltaPaise, int deltaCount) {
    TenantScope.require();
    jdbc.sql("INSERT INTO reco.spend_month (account_id, category_root, month, total_paise, txn_count, updated_at)"
            + " VALUES (:a, :root, :month, GREATEST(0, :delta), GREATEST(0, :count), now())"
            + " ON CONFLICT (account_id, category_root, month) DO UPDATE"
            + " SET total_paise = GREATEST(0, reco.spend_month.total_paise + :delta),"
            + "     txn_count   = GREATEST(0, reco.spend_month.txn_count + :count),"
            + "     updated_at  = now()")
        .param("a", account).param("root", Sql.ltree(categoryRoot)).param("month", month)
        .param("delta", deltaPaise).param("count", deltaCount).update();
  }

  @Override
  public List<MonthRow> monthsOf(UUID account, String categoryRoot, LocalDate from, LocalDate to) {
    TenantScope.require();
    return jdbc.sql("SELECT category_root::text AS category_root, month, total_paise, txn_count"
            + " FROM reco.spend_month WHERE account_id = :a AND category_root = :root"
            + " AND month BETWEEN :from AND :to ORDER BY month")
        .param("a", account).param("root", Sql.ltree(categoryRoot)).param("from", from).param("to", to)
        .query(MONTH).list();
  }

  @Override
  public void putProfile(UUID account, String categoryRoot, List<ProfileRow> windows, long projectedVersion) {
    TenantScope.require();
    for (ProfileRow w : windows) {
      jdbc.sql("INSERT INTO reco.spend_profile (account_id, category_root, window_code, monthly_avg_paise,"
              + " total_paise, txn_count, projected_version, updated_at)"
              + " VALUES (:a, :root, :w, :avg, :total, :count, :v, now())"
              + " ON CONFLICT (account_id, category_root, window_code) DO UPDATE"
              + " SET monthly_avg_paise = :avg, total_paise = :total, txn_count = :count,"
              + "     projected_version = GREATEST(reco.spend_profile.projected_version, :v), updated_at = now()")
          .param("a", account).param("root", Sql.ltree(categoryRoot)).param("w", w.window())
          .param("avg", w.monthlyAvgPaise()).param("total", w.totalPaise()).param("count", w.txnCount())
          .param("v", projectedVersion).update();
    }
  }

  @Override
  public void deleteProfile(UUID account, String categoryRoot) {
    TenantScope.require();
    jdbc.sql("DELETE FROM reco.spend_profile WHERE account_id = :a AND category_root = :root")
        .param("a", account).param("root", Sql.ltree(categoryRoot)).update();
  }

  @Override
  public List<ProfileRow> profile(UUID account, String window) {
    TenantScope.require();
    return jdbc.sql("SELECT category_root::text AS category_root, window_code, monthly_avg_paise, total_paise,"
            + " txn_count FROM reco.spend_profile WHERE account_id = :a AND window_code = :w"
            + " ORDER BY monthly_avg_paise DESC, category_root")
        .param("a", account).param("w", window).query(PROFILE).list();
  }

  // ---------------------------------------------------------------- held cards

  static final String UPSERT_HELD = "INSERT INTO reco.held_cards_proj (account_id, user_card_id,"
      + " card_product_id, projected_version, updated_at) VALUES (:a, :uc, :cp, :version, now())"
      + " ON CONFLICT (account_id, user_card_id) DO UPDATE"
      + " SET card_product_id = EXCLUDED.card_product_id, projected_version = EXCLUDED.projected_version,"
      + "     updated_at = now()"
      + " WHERE reco.held_cards_proj.projected_version < EXCLUDED.projected_version";

  @Override
  public boolean upsertHeldCard(UUID account, HeldCard card, long eventVersion) {
    TenantScope.require();
    // through the library guard (MASTER_PLAN 6.4): it refuses SQL that does not compare projected_version, so a
    // projection write cannot silently lose the out-of-order check
    return ProjectionVersionGuard.apply(jdbc, UPSERT_HELD,
        Map.of("a", account, "uc", card.userCardId(), "cp", card.cardProductId()), eventVersion);
  }

  static final String DELETE_HELD = "DELETE FROM reco.held_cards_proj WHERE account_id = :a"
      + " AND user_card_id = :uc AND projected_version < :version";

  /** A removed card leaves the projection; a stale remove (an older version) is ignored. */
  @Override
  public boolean deleteHeldCard(UUID account, UUID userCardId, long eventVersion) {
    TenantScope.require();
    if (ProjectionVersionGuard.apply(jdbc, DELETE_HELD, Map.of("a", account, "uc", userCardId), eventVersion)) {
      return true;
    }
    // nothing to delete: either already gone (a replay of the remove) or a newer version is stored
    return jdbc.sql("SELECT count(1) FROM reco.held_cards_proj WHERE account_id = :a AND user_card_id = :uc")
        .param("a", account).param("uc", userCardId).query(Long.class).single() == 0;
  }

  @Override
  public List<HeldCard> heldCards(UUID account) {
    TenantScope.require();
    return jdbc.sql("SELECT user_card_id, card_product_id, projected_version FROM reco.held_cards_proj"
            + " WHERE account_id = :a ORDER BY user_card_id")
        .param("a", account).query(HELD).list();
  }

  // ---------------------------------------------------------------- catalog projection

  /** The card and its rules in one transaction; a stale event changes nothing and answers false. */
  @Override
  public boolean upsertCard(CardRow card, List<RuleRow> rules, long eventVersion) {
    TenantScope.require();
    Map<String, Object> m = new HashMap<>();
    m.put("id", card.cardProductId());
    m.put("slug", card.slug());
    m.put("name", card.name());
    m.put("issuer", card.issuerCode());
    m.put("network", card.network());
    m.put("tier", card.tier());
    m.put("fee", card.annualFeePaise());
    m.put("waiver", card.feeWaiverSpendPaise());
    m.put("milestone", card.milestoneJson() == null ? null : Sql.jsonb(card.milestoneJson()));
    m.put("status", card.status());
    m.put("cv", card.currentVersion());
    if (!ProjectionVersionGuard.apply(jdbc, UPSERT_CARD, m, eventVersion)) {
      return false;
    }
    // a new version brings new rule ids, so the card's rules are replaced wholesale
    jdbc.sql("DELETE FROM reco.catalog_rules_proj WHERE card_product_id = :id")
        .param("id", card.cardProductId()).update();
    for (RuleRow r : rules) {
      Map<String, Object> p = new HashMap<>();
      p.put("id", r.cardProductId());
      p.put("rid", r.ruleId());
      p.put("path", r.categoryPath() == null ? null : Sql.ltree(r.categoryPath()));
      p.put("merchant", r.merchantId());
      p.put("type", r.rewardType());
      p.put("rate", r.rateBps());
      p.put("pv", r.pointValueMilliPaise());
      p.put("cap", r.capPaise());
      p.put("capPeriod", r.capPeriod());
      p.put("minTxn", r.minTxnPaise());
      p.put("excluded", Sql.texts(r.excludedPaths()));
      p.put("priority", r.priority());
      p.put("ver", eventVersion);
      jdbc.sql("INSERT INTO reco.catalog_rules_proj (card_product_id, rule_id, category_path, merchant_id,"
              + " reward_type, rate_bps, point_value_milli_paise, cap_paise, cap_period, min_txn_paise,"
              + " excluded_paths, priority, projected_version)"
              + " VALUES (:id, :rid, :path, :merchant, :type, :rate, :pv, :cap, :capPeriod, :minTxn, :excluded,"
              + " :priority, :ver)").params(p).update();
    }
    return true;
  }

  @Override
  public List<CardRow> publishedCards() {
    TenantScope.require();
    return jdbc.sql("SELECT " + CARD_COLS + " FROM reco.catalog_cards_proj WHERE status = 'published'"
        + " ORDER BY card_product_id").query(CARD).list();
  }

  @Override
  public List<RuleRow> rulesOf(List<UUID> cardProductIds) {
    TenantScope.require();
    if (cardProductIds.isEmpty()) {
      return List.of();
    }
    return jdbc.sql("SELECT " + RULE_COLS + " FROM reco.catalog_rules_proj"
            + " WHERE card_product_id = ANY(:ids) ORDER BY card_product_id, rule_id")
        .param("ids", Sql.uuids(cardProductIds)).query(RULE).list();
  }

  // ---------------------------------------------------------------- state

  @Override
  public Optional<StateRow> state(UUID account) {
    TenantScope.require();
    return jdbc.sql("SELECT account_id, ledger_ver, portfolio_ver, catalog_ver, dirty, dirty_since, computed_at,"
            + " attempts FROM reco.rec_state WHERE account_id = :a")
        .param("a", account).query(STATE).optional();
  }

  /**
   * Marks dirty and raises the source's high-water mark. {@code dirty_since} is set on the first event of a burst
   * and left alone afterwards — that is what the debounce measures.
   */
  @Override
  public void markDirty(UUID account, String source, long version, Instant now) {
    TenantScope.require();
    String column = switch (source) {
      case "ledger" -> "ledger_ver";
      case "portfolio" -> "portfolio_ver";
      case "catalog" -> "catalog_ver";
      default -> throw new IllegalArgumentException("unknown source " + source);
    };
    jdbc.sql("INSERT INTO reco.rec_state (account_id, " + column + ", dirty, dirty_since)"
            + " VALUES (:a, :v, true, :now) ON CONFLICT (account_id) DO UPDATE"
            + " SET " + column + " = GREATEST(reco.rec_state." + column + ", :v), dirty = true,"
            + "     dirty_since = COALESCE(reco.rec_state.dirty_since, :now)")
        .param("a", account).param("v", version).param("now", Sql.ts(now)).update();
  }

  /**
   * Claims the oldest dirty accounts and clears the flag in the same statement, so a second sweeper cannot pick
   * them up while the first is recomputing. {@code SKIP LOCKED} is what lets every instance sweep at once.
   *
   * <p>The claim is {@code reco.claim_dirty}, a SECURITY DEFINER function owned by {@code reco_owner}, because a
   * sweep spans accounts and {@code rec_state}'s tenant policy shows the app pool only its own row — written here
   * as plain SQL it claimed nothing, whatever actor the job carried (A-84).
   */
  @Override
  public List<UUID> claimDirty(Instant olderThan, int batch) {
    TenantScope.require();
    return jdbc.sql("SELECT c.account_id FROM reco.claim_dirty(:before, :n) AS c(account_id)")
        .param("before", Sql.ts(olderThan)).param("n", batch).query(UUID.class).list();
  }

  @Override
  public void markComputed(UUID account, Instant now) {
    TenantScope.require();
    jdbc.sql("INSERT INTO reco.rec_state (account_id, dirty, dirty_since, computed_at, attempts)"
            + " VALUES (:a, false, NULL, :now, 0) ON CONFLICT (account_id) DO UPDATE"
            + " SET dirty = false, dirty_since = NULL, computed_at = :now, attempts = 0")
        .param("a", account).param("now", Sql.ts(now)).update();
  }

  /** A failed recompute goes back on the queue with its attempt counted. */
  @Override
  public void markAttempt(UUID account) {
    TenantScope.require();
    jdbc.sql("UPDATE reco.rec_state SET dirty = true, dirty_since = COALESCE(dirty_since, now()),"
            + " attempts = attempts + 1 WHERE account_id = :a").param("a", account).update();
  }

  // ---------------------------------------------------------------- results

  @Override
  public Optional<ResultRow> result(UUID account) {
    TenantScope.require();
    return jdbc.sql("SELECT account_id, state_hash, computed_at, payload::text AS payload"
            + " FROM reco.recommendations WHERE account_id = :a")
        .param("a", account).query(RESULT).optional();
  }

  @Override
  public void putResult(ResultRow row) {
    TenantScope.require();
    jdbc.sql("INSERT INTO reco.recommendations (account_id, state_hash, computed_at, payload)"
            + " VALUES (:a, :h, :at, :p) ON CONFLICT (account_id) DO UPDATE"
            + " SET state_hash = :h, computed_at = :at, payload = :p")
        .param("a", row.accountId()).param("h", row.stateHash()).param("at", Sql.ts(row.computedAt()))
        .param("p", Sql.jsonb(row.payloadJson())).update();
    jdbc.sql("INSERT INTO reco.recommendation_history (account_id, computed_at, state_hash, payload)"
            + " VALUES (:a, :at, :h, :p) ON CONFLICT (account_id, computed_at) DO NOTHING")
        .param("a", row.accountId()).param("at", Sql.ts(row.computedAt())).param("h", row.stateHash())
        .param("p", Sql.jsonb(row.payloadJson())).update();
  }
}
