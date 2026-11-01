package in.dexter.reco;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.dexter.core.error.DexterException;
import in.dexter.reco.app.ProjectionService;
import in.dexter.reco.app.RecommendationService;
import in.dexter.reco.app.StreamService;
import in.dexter.reco.support.Events;
import in.dexter.reco.support.RecoIT;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * The SSE side of P15, including both tests the plan names: a ticket may be used once only, and a push reaches a
 * viewer attached to a <em>different instance</em>.
 *
 * <p>The second instance here is a second {@link StreamService} with its own Redis listener, sharing this context's
 * Redis. That is the whole of what "another instance" means for this mechanism — a separate in-memory registry of
 * emitters and a separate subscription — and it makes the test deterministic rather than standing up a second
 * Spring context and a second HTTP port to assert the same thing.
 */
class StreamIT extends RecoIT {
  @Autowired
  ProjectionService projections;
  @Autowired
  RecommendationService recommendations;
  @Autowired
  StreamService streams;
  @Autowired
  StringRedisTemplate redis;
  @Autowired
  in.dexter.reco.app.RecoStore store;
  @Autowired
  RedisConnectionFactory redisFactory;

  /** Instance B: its own service and its own subscription, the same Redis. */
  private StreamService other;
  private RedisMessageListenerContainer otherListener;

  static String today() {
    return LocalDate.now(ZoneId.of("Asia/Kolkata")).toString();
  }

  /** A Sink that records what it was sent, standing in for a browser's open connection. */
  static final class Recorder implements StreamService.Sink {
    final List<String> events = new CopyOnWriteArrayList<>();
    volatile boolean closed;
    volatile boolean broken;

    @Override
    public boolean send(String eventId, String eventName, String json) {
      if (broken) {
        return false;
      }
      events.add(eventName + "|" + (eventId == null ? "-" : eventId));
      return true;
    }

    @Override
    public void close() {
      closed = true;
    }
  }

  private StreamService instanceB() {
    if (other == null) {
      other = new StreamService(redis, store, JsonMapper.builder().build(), Clock.systemUTC());
      otherListener = new RedisMessageListenerContainer();
      otherListener.setConnectionFactory(redisFactory);
      otherListener.addMessageListener((message, pattern) -> other.onMessage(
          new String(message.getChannel(), StandardCharsets.UTF_8),
          new String(message.getBody(), StandardCharsets.UTF_8)),
          new PatternTopic(StreamService.CHANNEL_PREFIX + "*"));
      otherListener.afterPropertiesSet();
      otherListener.start();
    }
    return other;
  }

  @AfterEach
  void stopInstanceB() {
    if (otherListener != null) {
      otherListener.stop();
      otherListener.destroy();
      otherListener = null;
      other = null;
    }
  }

  void publishCard() {
    UUID cardId = UUID.randomUUID();
    projections.onCatalogEvent(Events.cardPublished(cardId, "stream-" + cardId, 1, 1,
        Events.snapshot(cardId, "stream-" + cardId, "Stream Card", 0, null,
            List.of(Events.rule(UUID.randomUUID(), "food", "cashback", 500, 0L, null, null, List.of(), 0)),
            null)));
  }

  // ------------------------------------------------------------------------------- the plan's named tests

  @Test
  void aTicketCanBeUsedOnceOnly() {
    UUID a = UUID.randomUUID();
    StreamService.Ticket t = streams.issue(in.dexter.core.tenant.Tenant.user(a));
    assertThat(t.ticket()).isNotBlank();
    assertThat(t.expiresInSeconds()).isEqualTo(StreamService.TICKET_TTL.toSeconds());

    assertThat(streams.consume(t.ticket())).as("the first use names the account").isEqualTo(a);
    assertThatThrownBy(() -> streams.consume(t.ticket())).as("the second use fails")
        .isInstanceOf(DexterException.class);

    assertThatThrownBy(() -> streams.consume("never-issued")).isInstanceOf(DexterException.class);
    assertThatThrownBy(() -> streams.consume(null)).isInstanceOf(DexterException.class);
    assertThatThrownBy(() -> streams.consume("")).isInstanceOf(DexterException.class);
  }

  @Test
  void aPushReachesAViewerOnAnotherInstance() {
    UUID a = UUID.randomUUID();
    publishCard();
    projections.onExpenseEvent(Events.created(a, UUID.randomUUID(),
        Events.state(900_000L, today(), "food.dining")));

    // the viewer is attached to instance B, and the recompute happens on this one
    Recorder viewer = new Recorder();
    instanceB().open(a, viewer, null);
    assertThat(instanceB().openStreams(a)).isEqualTo(1);
    assertThat(viewer.events).as("an opening event goes out at once").hasSize(1);
    viewer.events.clear();

    RecommendationService.Recomputed first = recommendations.recompute(a);
    assertThat(first.changed()).isTrue();
    assertThat(await(viewer, 1)).as("the push crossed Redis to the other instance")
        .containsExactly("recommendation.updated|" + first.stateHash());

    // a recompute that changes nothing publishes nothing
    viewer.events.clear();
    assertThat(recommendations.recompute(a).changed()).isFalse();
    quiet();
    assertThat(viewer.events).as("an unchanged recompute is not a notification").isEmpty();

    // more spend: a second push, with the new hash as its event id
    projections.onExpenseEvent(Events.created(a, UUID.randomUUID(),
        Events.state(400_000L, today(), "food.dining")));
    RecommendationService.Recomputed second = recommendations.recompute(a);
    assertThat(second.stateHash()).isNotEqualTo(first.stateHash());
    assertThat(await(viewer, 1)).containsExactly("recommendation.updated|" + second.stateHash());
  }

  // ------------------------------------------------------------------------------- the rest of the mechanism

  @Test
  void theOpeningEventSaysWhetherTheClientAlreadyHasThisResult() {
    UUID a = UUID.randomUUID();
    publishCard();
    projections.onExpenseEvent(Events.created(a, UUID.randomUUID(),
        Events.state(700_000L, today(), "food.dining")));
    String hash = recommendations.recompute(a).stateHash();

    Recorder fresh = new Recorder();
    streams.open(a, fresh, null);
    assertThat(fresh.events).containsExactly("recommendation.updated|" + hash);

    Recorder reconnect = new Recorder();
    streams.open(a, reconnect, hash);
    assertThat(reconnect.events).as("a reconnect that already has it is told so")
        .containsExactly("recommendation.unchanged|" + hash);
    streams.close(a, fresh);
    streams.close(a, reconnect);
  }

  @Test
  void anAccountWithNothingComputedIsToldSoRatherThanLeftWaiting() {
    UUID a = UUID.randomUUID();
    Recorder r = new Recorder();
    streams.open(a, r, null);
    assertThat(r.events).containsExactly("recommendation.none|-");
    streams.close(a, r);
  }

  @Test
  void theHeartbeatGoesToEveryStreamAndForgetsTheDeadOnes() {
    UUID a = UUID.randomUUID();
    Recorder alive = new Recorder();
    Recorder dead = new Recorder();
    streams.open(a, alive, null);
    streams.open(a, dead, null);
    assertThat(streams.openStreams(a)).isEqualTo(2);
    alive.events.clear();

    dead.broken = true;
    streams.heartbeat();
    assertThat(alive.events).containsExactly("heartbeat|-");
    assertThat(streams.openStreams(a)).as("the dead one is dropped").isEqualTo(1);
    assertThat(dead.closed).as("and closed").isTrue();
    streams.close(a, alive);
    assertThat(streams.openStreams(a)).isZero();
  }

  @Test
  void aMessageForAnotherAccountOrAMalformedOneIsIgnored() {
    UUID a = UUID.randomUUID();
    Recorder r = new Recorder();
    streams.open(a, r, null);
    r.events.clear();

    streams.onMessage("reco:acct:" + UUID.randomUUID(), "{\"stateHash\":\"x\",\"payload\":\"{}\"}");
    streams.onMessage("some:other:channel", "{\"stateHash\":\"x\",\"payload\":\"{}\"}");
    streams.onMessage(StreamService.CHANNEL_PREFIX + "not-a-uuid", "{}");
    streams.onMessage(StreamService.CHANNEL_PREFIX + a, "not json at all");
    assertThat(r.events).isEmpty();

    streams.onMessage(StreamService.CHANNEL_PREFIX + a, "{\"stateHash\":\"h1\",\"payload\":\"{}\"}");
    assertThat(r.events).containsExactly("recommendation.updated|h1");
    streams.close(a, r);
  }

  // ------------------------------------------------------------------------------- the HTTP surface

  @Test
  void theTicketEndpointAndTheStreamSEdgeBehaviour() {
    UUID a = UUID.randomUUID();
    assertThat(send("POST", "/v1/recommendations/stream-ticket", null, null).status())
        .as("anonymous: 401").isEqualTo(401);

    Res issued = send("POST", "/v1/recommendations/stream-ticket", null, null, auth(a));
    assertThat(issued.status()).as(issued.toString()).isEqualTo(200);
    String ticket = issued.json().get("ticket").asString();
    assertThat(ticket).isNotBlank();
    assertThat(issued.json().get("expiresInSeconds").asLong()).isEqualTo(60L);

    // the stream itself needs no token - the ticket is the credential, which is why the gateway makes it public
    assertThat(send("GET", "/v1/recommendations/stream?ticket=nonsense", null, null).status())
        .isEqualTo(401);
    assertThat(send("GET", "/v1/recommendations/stream?ticket=nonsense", null, null).code())
        .isEqualTo("STREAM_TICKET_INVALID");
    assertThat(send("GET", "/v1/recommendations/stream", null, null).status())
        .as("no ticket at all").isEqualTo(400);

    // the ticket names the account that asked for it
    assertThat(streams.consume(ticket)).isEqualTo(a);

    Res adminTicket = send("POST", "/v1/recommendations/stream-ticket", null, null,
        "Authorization", "Bearer " + IDP.user(a).audience("reco").scope("admin").sign());
    assertThat(adminTicket.status()).as("an admin console token cannot open a user stream").isEqualTo(403);
  }

  @Test
  void aTicketCarriesItsOwnExpiry() {
    UUID a = UUID.randomUUID();
    StreamService.Ticket t = streams.issue(in.dexter.core.tenant.Tenant.user(a));
    // pttl is in the per-service ACL; exists and ttl are not, so the remaining life is read through a script
    Long millis = redis.execute(new DefaultRedisScript<>("return redis.call('pttl', KEYS[1])", Long.class),
        List.of(StreamService.TICKET_PREFIX + t.ticket()));
    assertThat(millis).as("the ticket expires on its own rather than needing a sweeper")
        .isBetween(1L, StreamService.TICKET_TTL.toMillis());
  }

  /** The recorder's events once it has at least {@code n}, or whatever it has after a second. */
  static List<String> await(Recorder r, int n) {
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (r.events.size() < n && System.nanoTime() < deadline) {
      quiet();
    }
    return List.copyOf(r.events);
  }

  /** Long enough for a Redis message that was going to arrive to have arrived. */
  static void quiet() {
    try {
      Thread.sleep(50);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
