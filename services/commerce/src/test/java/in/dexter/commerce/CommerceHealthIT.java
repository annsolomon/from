package in.dexter.commerce;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.commerce.support.CommerceIT;
import org.junit.jupiter.api.Test;

/** P00 health + P01 web wiring, and P17's schema: three migrations as commerce_owner, placements seeded. */
class CommerceHealthIT extends CommerceIT {

  @Test
  void healthRequestIdAndCodedNotFound() {
    Res h = send("GET", "/actuator/health", null, "X-Request-Id", "smoke-test-1");
    assertThat(h.status()).isEqualTo(200);
    assertThat(h.body()).contains("\"status\":\"UP\"");
    assertThat(h.header("X-Request-Id")).isEqualTo("smoke-test-1");
    Res nf = send("GET", "/v1/__nope", null);
    assertThat(nf.status()).isEqualTo(404);
    assertThat(nf.body()).contains("\"code\":\"NOT_FOUND\"").contains("\"requestId\":");
  }

  @Test
  void migrationsRanAsTheOwner() {
    assertThat(count("SELECT count(1) FROM commerce.flyway_schema_history WHERE success AND type = 'SQL'"))
        .isEqualTo(4); // P18 V202611111000__commerce_privacy
    assertThat(ADMIN.sql("SELECT tableowner FROM pg_tables WHERE schemaname = 'commerce' AND tablename = 'deals'")
        .query(String.class).single()).isEqualTo("commerce_owner");
    assertThat(ADMIN.sql("SELECT string_agg(code, ',' ORDER BY code) FROM commerce.placements").query(String.class)
        .single()).isEqualTo("dashboard,deal-detail,home");
    // today's daily partitions exist for both raw tables (created ahead by the migration, then by the relay)
    assertThat(count("SELECT count(1) FROM pg_inherits i JOIN pg_class p ON p.oid = i.inhparent"
        + " WHERE p.relname = 'deal_clicks'")).isGreaterThanOrEqualTo(3);
    assertThat(count("SELECT count(1) FROM pg_inherits i JOIN pg_class p ON p.oid = i.inhparent"
        + " WHERE p.relname = 'ad_impressions_raw'")).isGreaterThanOrEqualTo(3);
  }
}
