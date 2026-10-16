package in.dexter.db.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import in.dexter.core.error.NotFoundException;
import in.dexter.core.error.VersionConflictException;
import in.dexter.db.testsupport.PgTestSupport;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class SqlBindingIT {
  enum Kind { CARD, UPI }

  record Row(UUID id, Instant createdAt, LocalDate day, long amountPaise, Integer version, String path,
      String[] tags, byte[] hash, Kind kind, Long optionalPaise, boolean active) {}

  static HikariDataSource ds;
  static JdbcClient jdbc;
  static final UUID ID1 = UUID.fromString("00000001-0000-7000-8000-000000000001");
  static final UUID ID2 = UUID.fromString("00000002-0000-7000-8000-000000000002");

  @BeforeAll
  static void setUp() {
    PgTestSupport.exec("demo_owner", """
        CREATE TABLE demo.bind_t (id uuid PRIMARY KEY, created_at timestamptz NOT NULL, day date NOT NULL,
          amount_paise bigint NOT NULL, version integer NOT NULL DEFAULT 0, path ltree NOT NULL, tags text[] NOT NULL,
          hash bytea NOT NULL, kind text NOT NULL, optional_paise bigint, active boolean NOT NULL, doc jsonb);
        GRANT SELECT, INSERT, UPDATE ON demo.bind_t TO demo_app;
        """);
    ds = PgTestSupport.pool("demo_app", 1, "bind", false);
    jdbc = JdbcClient.create(ds);
    for (UUID id : List.of(ID1, ID2)) {
      jdbc.sql("""
          INSERT INTO demo.bind_t (id, created_at, day, amount_paise, path, tags, hash, kind, active, doc)
          VALUES (:id, :ts, :day, 12345, :path, :tags, :hash, 'card', true, :doc)""")
          .param("id", id).param("ts", Sql.ts(Instant.parse("2026-09-01T10:00:00.123456Z")))
          .param("day", LocalDate.parse("2026-09-01")).param("path", Sql.ltree("food.dining"))
          .param("tags", Sql.texts(List.of("a", "b"))).param("hash", new byte[] {1, 2})
          .param("doc", Sql.jsonb("{\"x\":1}")).update();
    }
  }

  @AfterAll
  static void tearDown() {
    ds.close();
  }

  @Test
  void anyUuidArrayAndRecordMapping() {
    List<Row> rows = jdbc.sql("""
        SELECT id, created_at, day, amount_paise, version, path::text AS path, tags, hash, kind, optional_paise, active
        FROM demo.bind_t WHERE id = ANY(:ids) ORDER BY id""")
        .param("ids", Sql.uuids(List.of(ID1, ID2))).query(RecordRowMapper.of(Row.class)).list();
    assertThat(rows).hasSize(2);
    Row r = rows.getFirst();
    assertThat(r.id()).isEqualTo(ID1);
    assertThat(r.createdAt()).isEqualTo(Instant.parse("2026-09-01T10:00:00.123456Z"));
    assertThat(r.day()).isEqualTo(LocalDate.parse("2026-09-01"));
    assertThat(r.path()).isEqualTo("food.dining");
    assertThat(r.tags()).containsExactly("a", "b");
    assertThat(r.hash()).containsExactly(1, 2);
    assertThat(r.kind()).isEqualTo(Kind.CARD);
    assertThat(r.optionalPaise()).isNull();
    assertThat(r.version()).isZero();
    assertThat(jdbc.sql("SELECT count(id) FROM demo.bind_t WHERE path <@ :p").param("p", Sql.ltree("food"))
        .query(Long.class).single()).isEqualTo(2);
    assertThat(jdbc.sql("SELECT doc ->> 'x' FROM demo.bind_t WHERE id = :id").param("id", ID1)
        .query(String.class).single()).isEqualTo("1");
  }

  @Test
  void optimisticUpdate() {
    int ok = jdbc.sql("UPDATE demo.bind_t SET amount_paise = 1, version = version + 1 WHERE id = :id AND version = :v")
        .param("id", ID2).param("v", 0).update();
    OptimisticUpdate.check(ok, () -> true, "bind_t", 0);
    int stale = jdbc.sql("UPDATE demo.bind_t SET amount_paise = 2, version = version + 1 WHERE id = :id AND version = :v")
        .param("id", ID2).param("v", 0).update();
    assertThatThrownBy(() -> OptimisticUpdate.check(stale, () -> exists(ID2), "bind_t", 0))
        .isInstanceOf(VersionConflictException.class);
    UUID missing = UUID.randomUUID();
    assertThatThrownBy(() -> OptimisticUpdate.check(0, () -> exists(missing), "bind_t", 0))
        .isInstanceOf(NotFoundException.class);
  }

  static boolean exists(UUID id) {
    return jdbc.sql("SELECT EXISTS (SELECT 1 FROM demo.bind_t WHERE id = :id)").param("id", id).query(Boolean.class).single();
  }
}
