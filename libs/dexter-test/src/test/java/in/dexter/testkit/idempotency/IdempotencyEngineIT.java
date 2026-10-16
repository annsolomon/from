package in.dexter.testkit.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import in.dexter.core.error.CoreErrors;
import in.dexter.core.error.DexterException;
import in.dexter.core.tenant.Tenant;
import in.dexter.db.maintenance.BatchPurge;
import in.dexter.idempotency.ApiResponse;
import in.dexter.idempotency.IdempotencyEngine;
import in.dexter.idempotency.IdempotencyPurgeJob;
import in.dexter.idempotency.IdempotentRequest;
import in.dexter.idempotency.IdempotentResponse;
import in.dexter.testkit.demo.config.DemoApp;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;

/** Engine behaviour not covered by the HTTP contract: Mode B phases, optional keys, purge. */
class IdempotencyEngineIT {
  final DemoApp app = DemoApp.get();
  final IdempotencyEngine engine = app.idempotency;

  IdempotentRequest req(String body) {
    return IdempotentRequest.of("POST", "/v1/payments", body, app.mapper);
  }

  @Test
  void modeBReservesResumesAndReplays() {
    Tenant t = Tenant.user(UUID.randomUUID());
    UUID k = UUID.randomUUID();
    Duration lease = Duration.ofSeconds(30);

    IdempotencyEngine.ExternalStart s1 = app.tx.write(t, () -> engine.beginExternal(t, k, req("{\"p\":1}"), lease));
    assertThat(s1).isEqualTo(new IdempotencyEngine.Proceed(null));
    app.tx.writeVoid(t, () -> engine.recordRecoveryPoint(t, k, "charge_created"));

    // a concurrent retry while the lease is live → 409
    assertThatThrownBy(() -> app.tx.write(t, () -> engine.beginExternal(t, k, req("{\"p\":1}"), lease)))
        .isInstanceOfSatisfying(DexterException.class,
            e -> assertThat(e.errorCode()).isEqualTo(CoreErrors.IDEMPOTENCY_IN_PROGRESS));
    // a different request with the same key → 422, even while in progress
    assertThatThrownBy(() -> app.tx.write(t, () -> engine.beginExternal(t, k, req("{\"p\":2}"), lease)))
        .isInstanceOfSatisfying(DexterException.class,
            e -> assertThat(e.errorCode()).isEqualTo(CoreErrors.IDEMPOTENCY_KEY_REUSED));

    // the first attempt dies: its lease expires, the retry takes over from the recovery point
    app.admin.sql("UPDATE demo.idempotency_keys SET locked_until = now() - interval '1 second' WHERE idem_key = :k")
        .param("k", k).update();
    IdempotencyEngine.ExternalStart s2 = app.tx.write(t, () -> engine.beginExternal(t, k, req("{\"p\":1}"), lease));
    assertThat(s2).isEqualTo(new IdempotencyEngine.Proceed("charge_created"));

    IdempotentResponse done = app.tx.write(t, () -> engine.completeExternal(t, k,
        new ApiResponse(201, Map.of("Location", "/v1/payments/1"), Map.of("status", "captured"))));
    assertThat(done.replayed()).isFalse();
    assertThat(done.body()).isEqualTo("{\"status\": \"captured\"}");

    IdempotencyEngine.ExternalStart s3 = app.tx.write(t, () -> engine.beginExternal(t, k, req("{\"p\":1}"), lease));
    assertThat(s3).isInstanceOfSatisfying(IdempotencyEngine.Replay.class, r -> {
      assertThat(r.response().replayed()).isTrue();
      assertThat(r.response().status()).isEqualTo(201);
      assertThat(r.response().body()).isEqualTo(done.body());
      assertThat(r.response().headers()).containsEntry("Location", "/v1/payments/1");
    });
  }

  @Test
  void modeBAbandonLetsTheRetryStartClean() {
    Tenant t = Tenant.user(UUID.randomUUID());
    UUID k = UUID.randomUUID();
    app.tx.write(t, () -> engine.beginExternal(t, k, req("{}"), Duration.ofMinutes(1)));
    app.tx.writeVoid(t, () -> engine.abandonExternal(t, k));
    assertThat(app.tx.write(t, () -> engine.beginExternal(t, k, req("{}"), Duration.ofMinutes(1))))
        .isEqualTo(new IdempotencyEngine.Proceed(null));
  }

  @Test
  void aNullKeyRunsTheWorkWithoutStoringAnything() {
    Tenant t = Tenant.user(UUID.randomUUID());
    IdempotentResponse r = app.tx.write(t, () -> engine.execute(t, null, req("{}"), () -> ApiResponse.of(200, Map.of("ok", true))));
    assertThat(r.replayed()).isFalse();
    assertThat(app.admin.sql("SELECT count(1) FROM demo.idempotency_keys WHERE account_id = :a")
        .param("a", t.accountId()).query(Long.class).single()).isZero();
  }

  @Test
  void theEngineRefusesToRunOutsideTenantTx() {
    Tenant t = Tenant.user(UUID.randomUUID());
    assertThatThrownBy(() -> engine.execute(t, UUID.randomUUID(), req("{}"), () -> ApiResponse.of(200, null)))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> app.tx.read(t, () -> engine.execute(t, UUID.randomUUID(), req("{}"),
        () -> ApiResponse.of(200, null)))).isInstanceOf(IllegalStateException.class);
    Tenant other = Tenant.user(UUID.randomUUID());
    assertThatThrownBy(() -> app.tx.write(t, () -> engine.execute(other, UUID.randomUUID(), req("{}"),
        () -> ApiResponse.of(200, null)))).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void purgeRemovesOnlyExpiredKeysAsTheRelayRole() {
    UUID acct = UUID.randomUUID();
    app.admin.sql("""
        INSERT INTO demo.idempotency_keys (account_id, idem_key, request_method, request_path, request_hash, state, created_at)
        SELECT :a, gen_random_uuid(), 'POST', '/x', '\\x00', 'completed',
               CASE WHEN g <= 6000 THEN now() - interval '25 hours' ELSE now() END
        FROM generate_series(1, 6010) g
        """).param("a", acct).update();
    try (HikariDataSource relay = app.db.pool("relay", 2, "purge")) {
      JdbcClient rj = JdbcClient.create(relay);
      assertThat(rj.sql("SELECT count(1) FROM demo.idempotency_keys WHERE account_id = :a").param("a", acct)
          .query(Long.class).single()).as("relay role sees only expired keys").isEqualTo(6000);
      BatchPurge.Result r = new IdempotencyPurgeJob(rj, new JdbcTransactionManager(relay), "demo", 100).purgeNow();
      assertThat(r.deleted()).isGreaterThanOrEqualTo(6000);
      assertThat(r.batches()).isGreaterThanOrEqualTo(2);
    }
    assertThat(app.admin.sql("SELECT count(1) FROM demo.idempotency_keys WHERE account_id = :a").param("a", acct)
        .query(Long.class).single()).isEqualTo(10);
  }
}
