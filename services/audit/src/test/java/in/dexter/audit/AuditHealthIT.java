package in.dexter.audit;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.audit.support.AuditIT;
import org.junit.jupiter.api.Test;

/** P00 health + P01 web wiring, and P18's schema: three migrations as audit_owner, partitions ahead, the genesis. */
class AuditHealthIT extends AuditIT {

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
    assertThat(count("SELECT count(1) FROM audit.flyway_schema_history WHERE success AND type = 'SQL'"))
        .as("platform, core, privacy").isEqualTo(3);
    assertThat(ADMIN.sql("SELECT tableowner FROM pg_tables WHERE schemaname = 'audit' AND tablename = 'chain_head'")
        .query(String.class).single()).isEqualTo("audit_owner");
    assertThat(count("SELECT count(1) FROM pg_inherits i JOIN pg_class p ON p.oid = i.inhparent"
        + " WHERE p.relname = 'audit_log'")).as("monthly, this month and ahead").isGreaterThanOrEqualTo(4);
    assertThat(count("SELECT count(1) FROM pg_inherits i JOIN pg_class p ON p.oid = i.inhparent"
        + " WHERE p.relname = 'activity_events'")).as("daily, today and ahead").isGreaterThanOrEqualTo(4);
    // the chain starts at the genesis hash: the head itself while the log is empty, else seq 1's prev_hash
    assertThat(count("SELECT count(1) FROM audit.chain_head h WHERE h.id = 1 AND sha256(convert_to("
        + "'dexter-audit-genesis', 'UTF8')) = CASE WHEN h.seq = 0 THEN h.hash ELSE (SELECT l.prev_hash FROM"
        + " audit.audit_log l WHERE l.seq = 1) END")).isEqualTo(1);
  }
}
