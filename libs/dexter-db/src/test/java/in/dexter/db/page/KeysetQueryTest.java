package in.dexter.db.page;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.dexter.core.error.CoreErrors;
import in.dexter.core.error.DexterException;
import java.util.List;
import org.junit.jupiter.api.Test;

class KeysetQueryTest {
  record Row(String id) {}

  static KeysetQuery.Builder<Row> base() {
    return KeysetQuery.<Row>builder("occurred_desc")
        .key("occurred_at", KeyType.TIMESTAMPTZ).key("id", KeyType.UUID)
        .mapper((rs, n) -> new Row(rs.getString("id")))
        .keysOf(r -> List.of(r.id(), r.id()));
  }

  @Test
  void twoVariantsNoOrPredicate() {
    KeysetQuery<Row> q = base().sql("SELECT id, occurred_at FROM demo.items WHERE account_id = (SELECT demo.app_account())").build();
    assertThat(q.firstPageSql()).isEqualTo(
        "SELECT id, occurred_at FROM demo.items WHERE account_id = (SELECT demo.app_account())"
            + " ORDER BY occurred_at DESC, id DESC LIMIT :__limit");
    assertThat(q.nextPageSql()).isEqualTo(
        "SELECT id, occurred_at FROM demo.items WHERE account_id = (SELECT demo.app_account())"
            + " AND (occurred_at, id) < (CAST(:__k0 AS timestamptz), CAST(:__k1 AS uuid))"
            + " ORDER BY occurred_at DESC, id DESC LIMIT :__limit");
    assertThat(q.nextPageSql().toUpperCase()).doesNotContain(" OR ").doesNotContain("OFFSET");
    // text blocks put WHERE on its own line
    assertThat(base().sql("SELECT id, occurred_at FROM demo.items\nWHERE account_id = (SELECT demo.app_account())").build()
        .nextPageSql()).contains("\nWHERE account_id");
  }

  @Test
  void ascendingSingleKey() {
    KeysetQuery<Row> q = KeysetQuery.<Row>builder("name_asc").direction(KeysetQuery.Direction.ASC)
        .key("name", KeyType.TEXT).sql("SELECT name FROM demo.t WHERE true")
        .mapper((rs, n) -> new Row(rs.getString(1))).keysOf(r -> List.of(r.id())).build();
    assertThat(q.nextPageSql()).endsWith("AND name > CAST(:__k0 AS text) ORDER BY name ASC LIMIT :__limit");
  }

  @Test
  void rejectsUnsafeDefinitions() {
    assertThatThrownBy(() -> base().sql("SELECT id FROM demo.items").build()).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> base().sql("SELECT id FROM demo.items WHERE x ORDER BY id").build()).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> base().sql("SELECT id FROM demo.items WHERE x LIMIT 5").build()).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SortKey("id; drop table x", KeyType.UUID)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void pageRequestLimits() {
    assertThat(PageRequest.of(null, null).limit()).isEqualTo(25);
    assertThat(PageRequest.of(100, " ").cursor()).isNull();
    for (int bad : new int[] {0, -1, 101}) {
      assertThatThrownBy(() -> PageRequest.of(bad, null)).isInstanceOf(DexterException.class)
          .satisfies(e -> assertThat(((DexterException) e).errorCode()).isEqualTo(CoreErrors.LIMIT_INVALID));
    }
  }

  @Test
  void keyTypesRoundTrip() {
    assertThat(KeyType.TIMESTAMPTZ.encode(java.time.Instant.parse("2026-09-01T10:00:00Z"))).isEqualTo("2026-09-01T10:00:00Z");
    assertThat(KeyType.TIMESTAMPTZ.decode("2026-09-01T10:00:00Z").toString()).isEqualTo("2026-09-01T10:00Z");
    assertThat(KeyType.BIGINT.decode("42")).isEqualTo(42L);
    assertThatThrownBy(() -> KeyType.UUID.decode("nope")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> KeyType.TEXT.encode(null)).isInstanceOf(IllegalArgumentException.class);
  }
}
