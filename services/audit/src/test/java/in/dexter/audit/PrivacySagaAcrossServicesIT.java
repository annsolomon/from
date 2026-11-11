package in.dexter.audit;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.audit.support.MemoryExportStore;
import in.dexter.audit.support.ScriptedSender;
import in.dexter.contracts.events.audit.PrivacyRequestedV1;
import in.dexter.core.id.Ids;
import in.dexter.events.EnvelopeCodec;
import in.dexter.events.InboxGuard;
import in.dexter.events.KafkaClients;
import in.dexter.events.OutboxWriter;
import in.dexter.events.consumer.EventConsumer;
import in.dexter.events.relay.OutboxRelay;
import in.dexter.events.topics.TopicRegistry;
import in.dexter.privacy.PrivacyAutoConfiguration;
import in.dexter.privacy.PrivacyParticipant;
import in.dexter.privacy.PrivacyRequests;
import in.dexter.security.jwt.JwtVerifier;
import in.dexter.testkit.containers.ServiceDb;
import in.dexter.testkit.containers.TestKafka;
import in.dexter.testkit.containers.TestPostgres;
import in.dexter.testkit.security.TestJwt;
import com.zaxxer.hikari.HikariDataSource;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.kafka.clients.producer.Producer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The named test of MASTER_PLAN P18: <b>the saga completes across all services, and each service holds no rows for
 * the account afterwards</b> — a multi-service slim run. The audit service runs for real (its relay, its three
 * consumers and its jobs on Testcontainers Kafka); each of the seven participants is its own database, migrated by
 * <em>its own</em> Flyway migrations from {@code services/<svc>}, filled with a row in every account table for two
 * accounts, and answered by the very library code the service runs ({@link PrivacyParticipant}: its consumer, its
 * outbox and its relay as {@code <svc>_relay}). An export, then a deletion, go through Kafka end to end; afterwards
 * {@code <svc>.privacy_remaining} is 0 in every service for the deleted account and untouched for the other one.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PrivacySagaAcrossServicesIT.Beans.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PrivacySagaAcrossServicesIT {
  static final List<String> SERVICES = List.of("identity", "ledger", "portfolio", "planning", "reco", "billing",
      "commerce");
  static final JsonMapper JSON = JsonMapper.builder().build();
  static final ServiceDb AUDIT = TestPostgres.flywayDb("audit");
  static final JdbcClient AUDIT_SU = JdbcClient.create(
      new SimpleDriverDataSource(new org.postgresql.Driver(), AUDIT.url(), "postgres", "postgres"));
  static final TestJwt IDP = TestJwt.create();
  static final MemoryExportStore STORE = new MemoryExportStore();
  static final ScriptedSender SENDER = new ScriptedSender();
  static final UUID A = UUID.fromString("0192f1e2-0000-7000-8000-0000000a18a1");
  static final UUID B = UUID.fromString("0192f1e2-0000-7000-8000-0000000a18b1");
  static final Map<String, ServiceDb> DBS = new LinkedHashMap<>();
  static final List<AutoCloseable> RUNNING = new ArrayList<>();
  static final HttpClient HTTP = HttpClient.newHttpClient();
  static final String CURSOR_KEY;
  static final String SUBJECT_KEY;

  static {
    SecureRandom r = new SecureRandom();
    byte[] b = new byte[32];
    r.nextBytes(b);
    CURSOR_KEY = Base64.getEncoder().encodeToString(b);
    r.nextBytes(b);
    SUBJECT_KEY = Base64.getEncoder().encodeToString(b);
  }

  @Value("${local.server.port}")
  int port;

  @TestConfiguration(proxyBeanMethods = false)
  static class Beans {
    @Bean
    JwtVerifier testJwtVerifier() {
      return IDP.verifier("audit", Clock.systemUTC());
    }

    @Bean
    @Primary
    MemoryExportStore sagaExportStore() {
      return STORE;
    }

    @Bean
    @Primary
    ScriptedSender sagaSender() {
      return SENDER;
    }
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    String kafka = TestKafka.bootstrapServers();
    r.add("spring.datasource.url", AUDIT::url);
    r.add("spring.datasource.password", () -> ServiceDb.PW);
    r.add("spring.flyway.password", () -> ServiceDb.PW);
    r.add("dexter.cursor.keys.c1", () -> CURSOR_KEY);
    r.add("dexter.audit.admin.db-url", AUDIT::url);
    r.add("dexter.audit.admin.db-password", () -> ServiceDb.PW);
    r.add("dexter.audit.store.endpoint", () -> "http://127.0.0.1:1");
    r.add("dexter.audit.privacy.subject-key", () -> SUBJECT_KEY);
    r.add("dexter.audit.privacy.services", () -> String.join(",", SERVICES));
    r.add("dexter.audit.privacy.phase-timeout", () -> "20s");
    r.add("dexter.audit.privacy.max-attempts", () -> "5");
    r.add("dexter.audit.jobs.enabled", () -> "true");
    r.add("dexter.audit.jobs.saga-every", () -> "300ms");
    r.add("dexter.audit.jobs.anchor-every", () -> "1h");
    r.add("dexter.audit.consumers.enabled", () -> "true");
    r.add("dexter.audit.consumers.bootstrap-servers", () -> kafka);
    r.add("dexter.audit.relay.enabled", () -> "true");
    r.add("dexter.audit.relay.url", AUDIT::url);
    r.add("dexter.audit.relay.username", () -> AUDIT.role("relay"));
    r.add("dexter.audit.relay.password", () -> ServiceDb.PW);
    r.add("dexter.audit.relay.bootstrap-servers", () -> kafka);
    r.add("dexter.audit.relay.partitions", () -> "1");
  }

  /** Every participant: migrated by its own migrations, filled for A and B, its library code running. */
  @BeforeAll
  static void participants() throws Exception {
    String kafka = TestKafka.bootstrapServers();
    EnvelopeCodec codec = new EnvelopeCodec(JSON);
    List<String> stages = EventConsumer.Settings.defaults("x", "x", "x", List.of("x")).stageNames();
    TopicRegistry topics = new TopicRegistry(1, (short) 1);
    for (String svc : SERVICES) {
      PrivacyAutoConfiguration.topics(svc, stages, 1, (short) 1).specs().forEach(topics::add);
    }
    topics.ensure(TestKafka.admin(), Duration.ofSeconds(30));
    for (String svc : SERVICES) {
      ServiceDb db = TestPostgres.flywayDb(svc);
      DBS.put(svc, db);
      Flyway.configure().dataSource(db.url(), db.role("owner"), ServiceDb.PW).schemas(svc)
          .locations("filesystem:" + Path.of("..", svc, "src", "main", "resources", "db", "migration")).load()
          .migrate();
      String fill = Files.readString(Path.of("src", "test", "resources", "privacy-fill", svc + ".sql"));
      for (UUID account : List.of(A, B)) {
        db.exec("postgres", fill.replace("${account}", account.toString()));
      }
      HikariDataSource app = db.pool("app", 4, svc + "-privacy");
      RUNNING.add(app);
      JdbcClient jdbc = JdbcClient.create(app);
      Set<String> phases = svc.equals("identity") ? Set.of("freeze", "erase", "shred", "export")
          : Set.of("erase", "export");
      PrivacyParticipant participant = new PrivacyParticipant(svc, phases, jdbc,
          new TransactionTemplate(new JdbcTransactionManager(app)),
          new OutboxWriter(jdbc, codec, svc, svc, Clock.systemUTC(), () -> null), new InboxGuard(jdbc, svc),
          (key, json) -> STORE.put(key, json, "application/json"), codec, Clock.systemUTC(), Duration.ofMinutes(2));
      Producer<String, byte[]> producer = KafkaClients.idempotentProducer(kafka, svc + "-privacy-it");
      EventConsumer consumer = new EventConsumer(EventConsumer.Settings.defaults(kafka, svc,
          PrivacyRequests.consumer(svc), List.of(PrivacyRequestedV1.TOPIC)), participant::handle, codec, producer,
          Clock.systemUTC());
      OutboxRelay relay = new OutboxRelay(db.unpooled("relay"), producer, OutboxRelay.Settings.defaults(svc),
          OutboxRelay.Hooks.NONE, List.of());
      consumer.start();
      relay.start();
      RUNNING.add(relay);
      RUNNING.add(consumer);
      RUNNING.add(producer::close);
    }
  }

  @AfterAll
  static void stop() {
    for (AutoCloseable c : RUNNING.reversed()) {
      try {
        c.close();
      } catch (Exception e) {
        // best effort
      }
    }
  }

  static long remaining(String svc, UUID account) {
    try (var c = DBS.get(svc).conn("postgres"); var s = c.prepareStatement("SELECT " + svc + ".privacy_remaining(?)")) {
      s.setObject(1, account);
      try (var rs = s.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  HttpResponse<String> call(String method, String path, String token, String... headers) {
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
        .timeout(Duration.ofSeconds(30)).method(method, HttpRequest.BodyPublishers.noBody())
        .header("Authorization", "Bearer " + token);
    for (int i = 0; i + 1 < headers.length; i += 2) {
      b.header(headers[i], headers[i + 1]);
    }
    try {
      return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  static String userToken(UUID account) {
    return IDP.user(account).audience("audit").authTime(Instant.now()).sign();
  }

  static String operatorToken() {
    return IDP.admin(UUID.fromString("0192f1e2-0000-7000-8000-0000000ad001"), "SUPPORT").scope("admin")
        .audience("audit").permissions("audit:read").sign();
  }

  /** Polls the request (as an operator: a completed deletion no longer belongs to its account) until it is done. */
  JsonNode await(UUID request, Duration limit) throws InterruptedException {
    long deadline = System.nanoTime() + limit.toNanos();
    JsonNode last = null;
    while (System.nanoTime() < deadline) {
      HttpResponse<String> r = call("GET", "/v1/admin/audit/privacy-requests/" + request, operatorToken());
      if (r.statusCode() == 200) {
        last = JSON.readTree(r.body());
        String status = last.get("status").asString();
        if (!status.equals("running")) {
          return last;
        }
      }
      Thread.sleep(250);
    }
    throw new AssertionError("privacy request " + request + " still running: " + last);
  }

  @Test
  void theSagaCompletesAcrossAllServicesAndLeavesNoRows() throws Exception {
    for (String svc : SERVICES) {
      assertThat(remaining(svc, A)).as(svc + ": A's rows before (filled, not empty)").isPositive();
      assertThat(remaining(svc, B)).as(svc + ": B's rows before").isPositive();
    }
    Map<String, Long> bBefore = new LinkedHashMap<>();
    SERVICES.forEach(s -> bBefore.put(s, remaining(s, B)));

    // export: every service's part through Kafka, zipped with audit's own
    HttpResponse<String> export = call("POST", "/v1/privacy/export", userToken(A), "Idempotency-Key",
        Ids.newId().toString());
    assertThat(export.statusCode()).as(export.body()).isEqualTo(202);
    UUID exportId = UUID.fromString(JSON.readTree(export.body()).get("id").asString());
    JsonNode exported = await(exportId, Duration.ofSeconds(120));
    assertThat(exported.get("status").asString()).as(exported.toString()).isEqualTo("completed");
    Map<String, String> files = unzip(STORE.objects.get("privacy/" + exportId + "/export.zip"));
    assertThat(files.keySet()).containsAll(SERVICES.stream().map(s -> s + ".json").toList())
        .contains("audit.json", "manifest.json");
    for (String svc : SERVICES) {
      JsonNode part = JSON.readTree(files.get(svc + ".json"));
      long rows = 0;
      for (JsonNode t : part) {
        rows += t.size();
      }
      assertThat(rows).as(svc + ": its part holds A's rows").isPositive();
      assertThat(files.get(svc + ".json")).as(svc + ": no secrets in a part").doesNotContain("password_hash")
          .doesNotContain("token_hash").doesNotContain("dek_enc").doesNotContain("secret_enc");
      assertThat(files.get(svc + ".json")).as(svc + ": nothing of B").doesNotContain(B.toString());
    }
    assertThat(JSON.readTree(files.get("identity.json")).get("accounts").get(0).get("email").asString())
        .startsWith("p18-");

    // delete: freeze -> erase -> shred -> local, every step through Kafka
    HttpResponse<String> delete = call("POST", "/v1/privacy/delete", userToken(A), "Idempotency-Key",
        Ids.newId().toString());
    assertThat(delete.statusCode()).as(delete.body()).isEqualTo(202);
    UUID deleteId = UUID.fromString(JSON.readTree(delete.body()).get("id").asString());
    JsonNode deleted = await(deleteId, Duration.ofSeconds(180));
    assertThat(deleted.get("status").asString()).as(deleted.toString()).isEqualTo("completed");
    for (String phase : List.of("freeze", "erase", "shred", "local")) {
      assertThat(deleted.get("steps").has(phase)).as("step " + phase).isTrue();
    }
    for (String svc : SERVICES) {
      assertThat(deleted.get("steps").get("erase").get(svc).get("done").asBoolean()).as(svc + " erased").isTrue();
    }

    // each service holds no rows for the account afterwards; the other account is untouched
    for (String svc : SERVICES) {
      assertThat(remaining(svc, A)).as(svc + ": A's rows after").isZero();
      assertThat(remaining(svc, B)).as(svc + ": B's rows after").isEqualTo(bBefore.get(svc));
    }
    JdbcClient identity = JdbcClient.create(new SimpleDriverDataSource(new org.postgresql.Driver(),
        DBS.get("identity").url(), "postgres", "postgres"));
    assertThat(identity.sql("SELECT status || '|' || email || '|' || (display_name IS NULL) FROM identity.accounts"
        + " WHERE id = ?").param(1, A).query(String.class).single())
        .isEqualTo("deleted|" + A + "@deleted.invalid|true");
    assertThat(identity.sql("SELECT length(dek_enc) || '|' || (destroyed_at IS NOT NULL) FROM identity.account_keys"
        + " WHERE account_id = ?").param(1, A).query(String.class).single()).as("crypto-shredded").isEqualTo("0|true");
    JdbcClient billing = JdbcClient.create(new SimpleDriverDataSource(new org.postgresql.Driver(),
        DBS.get("billing").url(), "postgres", "postgres"));
    assertThat(billing.sql("SELECT count(1) FROM billing.invoices i JOIN billing.subscriptions s ON s.id ="
        + " i.subscription_id WHERE s.status = 'canceled' AND s.account_id NOT IN (?, ?)").param(1, A).param(2, B)
        .query(Long.class).single()).as("A's invoice kept, re-keyed to a stranger, its subscription ended")
        .isEqualTo(1);
    // audit's own part: nothing of A left but the pseudonymous record
    assertThat(AUDIT_SU.sql("SELECT audit.privacy_remaining(?)").param(1, A).query(Long.class).single()).isZero();
    assertThat(AUDIT_SU.sql("SELECT count(1) FROM audit.privacy_requests WHERE subject_ref IS NOT NULL AND id IN"
        + " (?, ?)").param(1, exportId).param(2, deleteId).query(Long.class).single()).isEqualTo(2);
    assertThat(AUDIT_SU.sql("SELECT count(1) FROM audit.audit_log WHERE action = 'privacy.delete.completed'"
        + " AND target_type = 'subject'").query(Long.class).single()).isPositive();
  }

  static Map<String, String> unzip(byte[] zip) throws IOException {
    Map<String, String> out = new LinkedHashMap<>();
    try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
      ZipEntry e;
      while ((e = in.getNextEntry()) != null) {
        out.put(e.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
      }
    }
    return out;
  }
}
