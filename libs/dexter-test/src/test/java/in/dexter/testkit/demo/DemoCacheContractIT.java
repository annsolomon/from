package in.dexter.testkit.demo;

import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import in.dexter.cache.CacheMetrics;
import in.dexter.core.tenant.Tenant;
import in.dexter.testkit.containers.TestRedis;
import in.dexter.testkit.contract.CacheContract;
import in.dexter.testkit.contract.HttpResult;
import in.dexter.testkit.contract.LoadGate;
import in.dexter.testkit.demo.config.DemoApp;
import in.dexter.testkit.support.Mvc;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.http.MediaType;

/** ADR-0004 §10 on GET /demo/notes/{id} and GET /demo/notes (scope {@code notes}). */
class DemoCacheContractIT extends CacheContract {
  static final Pattern ID = Pattern.compile("\"id\":\"([0-9a-f-]{36})\"");
  final DemoApp app = DemoApp.get();

  @Override
  protected UUID create(UUID account, String title) throws Exception {
    HttpResult r = Mvc.call(app.mvc, post("/demo/notes").contentType(MediaType.APPLICATION_JSON)
        .header("Authorization", app.bearer(account)).header("Idempotency-Key", UUID.randomUUID().toString())
        .content("{\"title\":\"" + title + "\",\"kind\":\"note\",\"amountPaise\":100}"));
    Matcher m = ID.matcher(r.body());
    if (r.status() != 201 || !m.find()) {
      throw new AssertionError("create failed: " + r);
    }
    return UUID.fromString(m.group(1));
  }

  @Override
  protected void update(UUID account, UUID id, String title) {
    long version = app.admin.sql("SELECT version FROM demo.notes WHERE account_id = :a AND id = :id")
        .param("a", account).param("id", id).query(Long.class).single();
    app.service.rename(Tenant.user(account), id, version, title);
  }

  @Override
  protected HttpResult read(UUID account, UUID id) throws Exception {
    return Mvc.call(app.mvc, get("/demo/notes/" + id).header("Authorization", app.bearer(account)));
  }

  @Override
  protected HttpResult readList(UUID account) throws Exception {
    return Mvc.call(app.mvc, get("/demo/notes").param("limit", "25").header("Authorization", app.bearer(account)));
  }

  @Override
  protected long loads() {
    return app.loadProbe.loads.get();
  }

  @Override
  protected CacheMetrics metrics() {
    return app.cache.metrics();
  }

  @Override
  protected void slowLoads(Duration d) {
    app.loadProbe.slow = d;
  }

  @Override
  protected LoadGate pauseNextLoad() {
    return app.loadProbe.pauseNext();
  }

  @Override
  protected void cutRedis() {
    app.redisProxy.cut();
  }

  @Override
  protected void restoreRedis() throws Exception {
    restore(app);
  }

  /** Heals the proxy, waits for the demo's client to reconnect, then for the breaker window to pass. */
  static void restore(DemoApp app) throws InterruptedException {
    app.redisProxy.restore();
    await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(50)).ignoreExceptions()
        .until(() -> "PONG".equals(app.redis.execute((RedisCallback<String>) c -> c.ping())));
    Thread.sleep(DemoApp.BREAKER_OPEN.plusMillis(100));
  }

  @Override
  protected void deliverPathB(UUID account) {
    List<String> payloads = app.admin.sql("SELECT payload::text FROM demo.outbox WHERE message_key = :a"
            + " ORDER BY created_at, id").param("a", account.toString()).query(String.class).list();
    for (String p : payloads) {
      app.cacheInvalidation.handle(app.codec.decode(p));
    }
  }

  @Override
  protected long purge(UUID account) {
    return app.cache.invalidator().purgeAccount(account);
  }

  @Override
  protected Set<String> redisKeysOf(UUID account) {
    return TestRedis.keys("*" + account + "*");
  }
}
