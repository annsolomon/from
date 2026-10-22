package in.dexter.portfolio.infra;

import in.dexter.db.page.KeyType;
import in.dexter.db.page.KeysetPager;
import in.dexter.db.page.KeysetQuery;
import in.dexter.db.page.Page;
import in.dexter.db.page.PageRequest;
import in.dexter.db.sql.Rows;
import in.dexter.db.sql.Sql;
import in.dexter.db.tenant.TenantScope;
import in.dexter.portfolio.app.RewardStore;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The reward points ledger (append-only; RLS: the tenant's rows). */
public final class RewardRepository implements RewardStore {
  static final String COLS = "id, account_id, user_card_id, kind, points, value_milli_paise, expires_on, source_ref,"
      + " card_txn_id, note, created_at";
  static final RowMapper<Entry> ENTRY = (rs, n) -> new Entry(Rows.uuid(rs, "id"), Rows.uuid(rs, "account_id"),
      Rows.uuid(rs, "user_card_id"), rs.getString("kind"), rs.getLong("points"), rs.getLong("value_milli_paise"),
      Rows.date(rs, "expires_on"), rs.getString("source_ref"), Rows.uuid(rs, "card_txn_id"), rs.getString("note"),
      Rows.instant(rs, "created_at"));
  static final List<String> PREDICATES = List.of(" AND user_card_id = :card");
  private final Map<Integer, KeysetQuery<Entry>> queries = new ConcurrentHashMap<>();

  static KeysetQuery<Entry> listQuery(int mask) {
    return KeysetQuery.<Entry>builder("created_desc")
        .direction(KeysetQuery.Direction.DESC)
        .key("created_at", KeyType.TIMESTAMPTZ)
        .key("id", KeyType.UUID)
        .sql("SELECT " + COLS + " FROM portfolio.reward_points_ledger WHERE account_id = (SELECT portfolio.app_account())"
            + (mask == 1 ? PREDICATES.getFirst() : ""))
        .mapper(ENTRY)
        .keysOf(e -> List.of(e.createdAt(), e.id()))
        .build();
  }

  private final JdbcClient jdbc;
  private final KeysetPager pager;

  public RewardRepository(JdbcClient jdbc, KeysetPager pager) {
    this.jdbc = jdbc;
    this.pager = pager;
  }

  @Override
  public boolean append(Entry e) {
    TenantScope.require();
    Map<String, Object> m = new HashMap<>();
    m.put("id", e.id());
    m.put("a", e.accountId());
    m.put("card", e.userCardId());
    m.put("kind", e.kind());
    m.put("points", e.points());
    m.put("value", e.valueMilliPaise());
    m.put("exp", e.expiresOn());
    m.put("ref", e.sourceRef());
    m.put("txn", e.cardTxnId());
    m.put("note", e.note());
    m.put("now", Sql.ts(e.createdAt()));
    return jdbc.sql("INSERT INTO portfolio.reward_points_ledger (" + COLS + ") VALUES (:id, :a, :card, :kind, :points,"
            + " :value, :exp, :ref, :txn, :note, :now) ON CONFLICT (account_id, source_ref)"
            + " WHERE source_ref IS NOT NULL DO NOTHING")
        .params(m).update() == 1;
  }

  @Override
  public Page<Entry> list(PageRequest page, UUID cardId) {
    TenantScope.require();
    int mask = cardId == null ? 0 : 1;
    Map<String, Object> p = cardId == null ? Map.of() : Map.of("card", cardId);
    Map<String, String> f = cardId == null ? Map.of() : Map.of("cardId", cardId.toString());
    return pager.page(jdbc, queries.computeIfAbsent(mask, RewardRepository::listQuery), page, p, f);
  }

  @Override
  public List<Entry> entries(UUID cardId) {
    TenantScope.require();
    return jdbc.sql("SELECT " + COLS + " FROM portfolio.reward_points_ledger WHERE account_id ="
            + " (SELECT portfolio.app_account()) AND user_card_id = :card ORDER BY created_at, id LIMIT 100000")
        .param("card", cardId).query(ENTRY).list();
  }

  @Override
  public List<UUID> cardsWithPoints() {
    TenantScope.require();
    return jdbc.sql("SELECT DISTINCT user_card_id FROM portfolio.reward_points_ledger"
        + " WHERE account_id = (SELECT portfolio.app_account())").query(UUID.class).list();
  }
}
