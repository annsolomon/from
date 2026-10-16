package in.dexter.db.page;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import in.dexter.core.error.CoreErrors;
import in.dexter.core.error.DexterException;
import in.dexter.core.id.Uuids;
import in.dexter.core.tenant.Tenant;
import in.dexter.db.plan.PlanAssert;
import in.dexter.db.sql.RecordRowMapper;
import in.dexter.db.sql.Sql;
import in.dexter.db.tenant.TenantTx;
import in.dexter.db.testsupport.PgTestSupport;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;

/** MASTER_PLAN 4.5 items 1-9 at library level (the per-endpoint PaginationContract comes in P02). */
class KeysetPagerIT {
  record Item(UUID id, Instant occurredAt, long amountPaise, String category) {}

  static final Tenant A = Tenant.user(UUID.fromString("0000000a-0000-7000-8000-00000000000a"));
  static final Tenant B = Tenant.user(UUID.fromString("0000000b-0000-7000-8000-00000000000b"));
  static final Instant SAME = Instant.parse("2026-09-01T10:00:00Z");
  static HikariDataSource ds;
  static JdbcClient jdbc;
  static TenantTx tx;
  static KeysetPager pager;
  static final KeysetQuery<Item> ALL = query("");
  static final KeysetQuery<Item> BY_CATEGORY = query(" AND category = :category");

  static KeysetQuery<Item> query(String extra) {
    return KeysetQuery.<Item>builder("occurred_desc")
        .key("occurred_at", KeyType.TIMESTAMPTZ).key("id", KeyType.UUID)
        .sql("SELECT id, occurred_at, amount_paise, category FROM demo.items"
            + " WHERE account_id = (SELECT demo.app_account())" + extra)
        .mapper(RecordRowMapper.of(Item.class))
        .keysOf(i -> List.of(i.occurredAt(), i.id()))
        .build();
  }

  @BeforeAll
  static void setUp() {
    PgTestSupport.exec("demo_owner", """
        CREATE TABLE demo.items (id uuid PRIMARY KEY, account_id uuid NOT NULL, occurred_at timestamptz NOT NULL,
          amount_paise bigint NOT NULL CHECK (amount_paise > 0), category text NOT NULL);
        CREATE INDEX items_list ON demo.items (account_id, occurred_at DESC, id DESC);
        ALTER TABLE demo.items ENABLE ROW LEVEL SECURITY;
        ALTER TABLE demo.items FORCE ROW LEVEL SECURITY;
        CREATE POLICY items_tenant ON demo.items FOR ALL TO demo_app
          USING (account_id = (SELECT demo.app_account())) WITH CHECK (account_id = (SELECT demo.app_account()));
        GRANT SELECT, INSERT, UPDATE, DELETE ON demo.items TO demo_app;
        """);
    // 1,000 rows for A (200 share one timestamp), 300 for B; plus 50k background rows for the plan check.
    PgTestSupport.exec("postgres", """
        INSERT INTO demo.items
        SELECT gen_random_uuid(), '0000000a-0000-7000-8000-00000000000a',
               CASE WHEN g <= 200 THEN timestamptz '2026-09-01T10:00:00Z' ELSE timestamptz '2026-01-01' + g * interval '1 hour' END,
               g, CASE WHEN g % 2 = 0 THEN 'food' ELSE 'travel' END FROM generate_series(1, 1000) g;
        INSERT INTO demo.items
        SELECT gen_random_uuid(), '0000000b-0000-7000-8000-00000000000b', timestamptz '2026-01-01' + g * interval '1 hour', g, 'food'
        FROM generate_series(1, 300) g;
        INSERT INTO demo.items
        SELECT gen_random_uuid(), gen_random_uuid(), timestamptz '2025-01-01' + g * interval '1 minute', g, 'food'
        FROM generate_series(1, 50000) g;
        ANALYZE demo.items;
        """);
    ds = PgTestSupport.pool("demo_app", 2, "pager", false);
    jdbc = JdbcClient.create(ds);
    tx = new TenantTx(new JdbcTransactionManager(ds), jdbc);
    pager = new KeysetPager(new KeysetCursorCodec(new CursorKeyRing("c1",
        Map.of("c1", "pager-it-key-0123456789abcdefghijklmnop".getBytes(StandardCharsets.UTF_8)))));
  }

  @AfterAll
  static void tearDown() {
    ds.close();
  }

  static Page<Item> page(Tenant t, KeysetQuery<Item> q, int limit, String cursor, Map<String, ?> filters) {
    return tx.read(t, () -> pager.page(jdbc, q, new PageRequest(limit, cursor), filters, filters));
  }

  static List<Item> walk(Tenant t, KeysetQuery<Item> q, Map<String, ?> filters, Runnable afterFirstPage) {
    List<Item> all = new ArrayList<>();
    Page<Item> p = page(t, q, 50, null, filters);
    all.addAll(p.items());
    afterFirstPage.run();
    while (p.hasMore()) {
      p = page(t, q, 25, p.nextCursor(), filters);
      all.addAll(p.items());
      assertThat(p.items()).hasSizeLessThanOrEqualTo(25);
    }
    assertThat(p.nextCursor()).isNull();
    return all;
  }

  @Test
  void firstPageOf50HasMore() {
    Page<Item> p = page(A, ALL, 50, null, Map.of());
    assertThat(p.items()).hasSize(50);
    assertThat(p.hasMore()).isTrue();
    assertThat(p.nextCursor()).isNotBlank();
  }

  @Test
  void walkYieldsEveryRowExactlyOnceInOrderIncludingDuplicateTimestamps() {
    List<Item> all = walk(A, ALL, Map.of(), () -> {});
    assertThat(all).hasSize(1000);
    assertThat(new HashSet<>(all.stream().map(Item::id).toList())).hasSize(1000);
    assertThat(all.stream().filter(i -> i.occurredAt().equals(SAME)).count()).isEqualTo(200);
    for (int i = 1; i < all.size(); i++) {
      Item prev = all.get(i - 1);
      Item cur = all.get(i);
      int c = prev.occurredAt().compareTo(cur.occurredAt());
      // Uuids.POSTGRES_ORDER, not UUID.compareTo: Postgres compares uuid as unsigned bytes.
      assertThat(c > 0 || (c == 0 && Uuids.compare(prev.id(), cur.id()) > 0))
          .as("strict (occurred_at, id) DESC order at %d", i).isTrue();
    }
  }

  @Test
  void rowsInsertedAboveTheCursorMidWalkCauseNoDuplicates() {
    Set<UUID> inserted = new HashSet<>();
    List<Item> all = walk(B, ALL, Map.of(), () -> tx.writeVoid(B, () -> {
      for (int i = 0; i < 20; i++) {
        UUID id = UUID.randomUUID();
        inserted.add(id);
        jdbc.sql("INSERT INTO demo.items (id, account_id, occurred_at, amount_paise, category) VALUES (:id, :a, :t, 1, 'food')")
            .param("id", id).param("a", B.accountId()).param("t", Sql.ts(Instant.parse("2027-01-01T00:00:00Z"))).update();
      }
    }));
    assertThat(all.stream().map(Item::id).distinct().count()).isEqualTo(all.size());
    assertThat(all.stream().map(Item::id).filter(inserted::contains)).isEmpty();
    assertThat(all).hasSize(300);
  }

  @Test
  void filtersAreBoundIntoTheCursor() {
    Page<Item> food = page(A, BY_CATEGORY, 25, null, Map.of("category", "food"));
    assertThat(food.items()).allMatch(i -> i.category().equals("food"));
    assertThatThrownBy(() -> page(A, BY_CATEGORY, 25, food.nextCursor(), Map.of("category", "travel")))
        .isInstanceOf(DexterException.class)
        .satisfies(e -> assertThat(((DexterException) e).errorCode()).isEqualTo(CoreErrors.CURSOR_FILTER_MISMATCH));
    assertThat(walk(A, BY_CATEGORY, Map.of("category", "food"), () -> {})).hasSize(500);
  }

  @Test
  void tamperedCursorIsRejected() {
    String c = page(A, ALL, 25, null, Map.of()).nextCursor();
    String bad = c.substring(0, 5) + (c.charAt(5) == 'x' ? 'y' : 'x') + c.substring(6);
    assertThatThrownBy(() -> page(A, ALL, 25, bad, Map.of())).isInstanceOf(DexterException.class)
        .satisfies(e -> assertThat(((DexterException) e).errorCode()).isEqualTo(CoreErrors.CURSOR_INVALID));
  }

  @Test
  void anotherTenantsCursorStillOnlyReturnsOwnRows() {
    String bCursor = page(B, ALL, 25, null, Map.of()).nextCursor();
    Page<Item> p = page(A, ALL, 100, bCursor, Map.of());
    assertThat(p.items()).isNotEmpty();
    Set<UUID> aIds = new HashSet<>(walk(A, ALL, Map.of(), () -> {}).stream().map(Item::id).toList());
    assertThat(p.items()).allMatch(i -> aIds.contains(i.id()));
  }

  @Test
  void planUsesTheIndexAndNoSort() {
    Page<Item> first = page(A, ALL, 25, null, Map.of());
    CursorPosition pos = new KeysetCursorCodec(new CursorKeyRing("c1",
        Map.of("c1", "pager-it-key-0123456789abcdefghijklmnop".getBytes(StandardCharsets.UTF_8))))
        .decode(first.nextCursor(), "occurred_desc", KeysetCursorCodec.filterHash(Map.of()));
    for (String sql : List.of(ALL.firstPageSql(), ALL.nextPageSql())) {
      tx.read(A, () -> PlanAssert.query(jdbc, sql, Map.of(
              "__limit", 26,
              "__k0", KeyType.TIMESTAMPTZ.decode(pos.keys().get(0)),
              "__k1", KeyType.UUID.decode(pos.keys().get(1))))
          .forbidSeqScanOn("items").forbidSort().requireIndex("items_list").verify());
    }
  }
}
