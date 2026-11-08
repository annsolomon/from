package in.dexter.commerce;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.commerce.app.CounterBuffer;
import in.dexter.commerce.domain.ClickToken;
import in.dexter.commerce.support.CommerceIT;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * The signed redirect and the counters (MASTER_PLAN P17, 8.3): a click is one raw row attributed to the token's
 * account, a 302 with the click id as subid, and one increment; and the flusher's drain is exact under
 * concurrency — every click lands in deal_click_counters once, however the flushes interleave with the clicks.
 */
class ClicksIT extends CommerceIT {
  @Autowired
  ClickToken tokens;
  @Autowired
  CounterBuffer counters;

  static String category() {
    return "clk" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
  }

  Res click(String path, String ua) {
    return send("GET", path, null, "User-Agent", ua);
  }

  @Test
  void aClickIsLoggedAttributedCountedAndRedirected() {
    UUID d = deal("Click me", category(), List.of(), 1_000_000);
    UUID viewer = UUID.randomUUID();
    String path = clickPath(d, viewer);
    Res r = click(path, "it-agent/1");
    assertThat(r.status()).as(r.toString()).isEqualTo(302);
    String location = r.header("Location");
    assertThat(location).startsWith("https://merchant.example/p?ref=dexter&subid=");
    assertThat(r.header("Cache-Control")).contains("no-store");
    assertThat(r.header("Referrer-Policy")).isEqualTo("no-referrer");
    UUID clickId = UUID.fromString(location.substring(location.indexOf("subid=") + 6));
    assertThat(ADMIN.sql("SELECT account_id FROM commerce.deal_clicks WHERE id = :id").param("id", clickId)
        .query(UUID.class).single()).as("attributed to the token's viewer, not the redirect request").isEqualTo(viewer);
    click(path, "it-agent/1");
    click(clickPath(d, null), "it-agent/2");
    flusher.flush();
    assertThat(count("SELECT clicks FROM commerce.deal_click_counters WHERE deal_id = ?", d)).isEqualTo(3);
    assertThat(count("SELECT uniques FROM commerce.deal_click_counters WHERE deal_id = ?", d))
        .as("the viewer once, the anonymous agent once").isEqualTo(2);
    assertThat(count("SELECT count(1) FROM commerce.deal_clicks WHERE deal_id = ?", d)).isEqualTo(3);
  }

  @Test
  void alteredExpiredAndEndedLinksNeverRedirect() {
    UUID d = deal("Ends soon", category(), List.of(), 1_000_000);
    String path = clickPath(d, null);
    char last = path.charAt(path.length() - 2);
    String altered = path.substring(0, path.length() - 2) + (last == 'A' ? 'B' : 'A') + path.charAt(path.length() - 1);
    assertThat(click(altered, "x").code()).isEqualTo("CLICK_TOKEN_INVALID");
    assertThat(click("/r/nope", "x").status()).isEqualTo(400);
    String expired = "/r/" + tokens.mint(new ClickToken.Claims(ClickToken.Kind.DEAL, d, null,
        Instant.now().minusSeconds(1)));
    Res e = click(expired, "x");
    assertThat(e.status()).isEqualTo(410);
    assertThat(e.code()).isEqualTo("CLICK_TOKEN_EXPIRED");
    Res end = admin("POST", "/v1/admin/commerce/deals/" + d + ":status", "{\"status\":\"ended\"}", "If-Match", "\"0\"");
    assertThat(end.status()).as(end.toString()).isEqualTo(200);
    assertThat(click(path, "x").code()).isEqualTo("DEAL_UNAVAILABLE");
    assertThat(count("SELECT count(1) FROM commerce.deal_clicks WHERE deal_id = ?", d)).isZero();
  }

  /** MASTER_PLAN P17 named test: counter flush correctness under concurrency. */
  @Test
  void counterFlushIsExactUnderConcurrency() throws Exception {
    String cat = category();
    UUID d1 = deal("Busy 1", cat, List.of(), 1_000_000);
    UUID d2 = deal("Busy 2", cat, List.of(), 1_000_000);
    List<String> paths = new ArrayList<>();
    for (int v = 0; v < 5; v++) {
      UUID viewer = UUID.randomUUID();
      paths.add(clickPath(d1, viewer));
      paths.add(clickPath(d2, viewer));
    }
    int threads = 8;
    int perThread = 25;
    ExecutorService pool = Executors.newFixedThreadPool(threads + 1);
    AtomicBoolean clicking = new AtomicBoolean(true);
    AtomicInteger flushes = new AtomicInteger();
    CountDownLatch start = new CountDownLatch(1);
    Future<?> flushing = pool.submit(() -> {
      start.await();
      while (clicking.get()) {
        flusher.flush();
        flushes.incrementAndGet();
      }
      return null;
    });
    List<Future<Integer>> clickers = new ArrayList<>();
    for (int t = 0; t < threads; t++) {
      int offset = t;
      clickers.add(pool.submit(() -> {
        start.await();
        int ok = 0;
        for (int i = 0; i < perThread; i++) {
          if (click(paths.get((offset + i) % paths.size()), "busy-agent").status() == 302) {
            ok++;
          }
        }
        return ok;
      }));
    }
    start.countDown();
    int redirected = 0;
    for (Future<Integer> f : clickers) {
      redirected += f.get(120, TimeUnit.SECONDS);
    }
    clicking.set(false);
    flushing.get(60, TimeUnit.SECONDS);
    flusher.flush();
    pool.shutdown();
    assertThat(redirected).isEqualTo(threads * perThread);
    assertThat(flushes.get()).as("the flusher ran while the clicks did").isGreaterThan(1);
    long raw = count("SELECT count(1) FROM commerce.deal_clicks WHERE deal_id IN (?, ?)", d1, d2);
    long counted = count("SELECT coalesce(sum(clicks), 0) FROM commerce.deal_click_counters WHERE deal_id IN (?, ?)",
        d1, d2);
    assertThat(raw).isEqualTo(200);
    assertThat(counted).as("every click counted exactly once").isEqualTo(raw);
    assertThat(count("SELECT coalesce(sum(uniques), 0) FROM commerce.deal_click_counters WHERE deal_id = ?", d1))
        .as("five viewers clicked deal 1").isEqualTo(5);
    assertThat(count("SELECT coalesce(sum(uniques), 0) FROM commerce.deal_click_counters WHERE deal_id = ?", d2))
        .isEqualTo(5);
    assertThat(redis().keys("commerce:cnt:*:" + d1)).as("nothing left pending").isEmpty();
  }

  @Test
  void aFailedWriteGivesItsDeltasBack() {
    UUID d = UUID.randomUUID();
    java.time.LocalDate day = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"));
    counters.restore(List.of(new CounterBuffer.Delta(CounterBuffer.Metric.DEAL_CLICKS, day, d, 7)));
    counters.restore(List.of(new CounterBuffer.Delta(CounterBuffer.Metric.DEAL_CLICKS, day, d, 3)));
    flusher.flush();
    assertThat(count("SELECT clicks FROM commerce.deal_click_counters WHERE deal_id = ?", d)).isEqualTo(10);
  }

  static StringRedisTemplate redis() {
    return in.dexter.testkit.containers.TestRedis.admin();
  }
}
