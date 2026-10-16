package in.dexter.db.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SqlTest {
  @Test
  void ltreeAndIdentValidation() {
    assertThat(Sql.isValidLtree("food.dining")).isTrue();
    assertThat(Sql.isValidLtree("food..x")).isFalse();
    assertThat(Sql.isValidLtree("food.din ing")).isFalse();
    assertThatThrownBy(() -> Sql.ltree("a';--")).isInstanceOf(IllegalArgumentException.class);
    assertThat(Sql.ident("outbox")).isEqualTo("outbox");
    assertThatThrownBy(() -> Sql.ident("Outbox\"; drop")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void snakeCase() {
    assertThat(RecordRowMapper.snake("occurredAt")).isEqualTo("occurred_at");
    assertThat(RecordRowMapper.snake("amountPaise")).isEqualTo("amount_paise");
    assertThat(RecordRowMapper.snake("id")).isEqualTo("id");
  }
}
