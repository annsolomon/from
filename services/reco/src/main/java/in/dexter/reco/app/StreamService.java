package in.dexter.reco.app;

import in.dexter.core.error.DexterException;
import in.dexter.core.tenant.Tenant;
import in.dexter.reco.domain.RecoErrors;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import tools.jackson.databind.ObjectMapper;

/**
 * The SSE side of P15: a one-time ticket, then a stream that pushes a new recommendation as soon as one is
 * computed — by any instance.
 *
 * <h2>Why a ticket</h2>
 *
 * <p>{@code EventSource} in a browser cannot set an {@code Authorization} header, so the stream cannot be opened
 * with a bearer token. The client therefore posts to {@code /v1/recommendations/stream-ticket} with its token and
 * gets a short, single-use ticket to put in the query string. The ticket is the credential, so it is treated like
 * one: 60 seconds of life, consumed atomically on first use, and it names one account and nothing else. The
 * gateway lists {@code GET /v1/recommendations/stream} as a public route for exactly this reason — the edge cannot
 * check a token that is not there, so this service checks the ticket instead.
 *
 * <h2>Why Redis pub/sub</h2>
 *
 * <p>A browser is connected to one instance, and the recompute that concerns it may happen on another — the
 * sweeper runs everywhere. An in-process listener would push only to whoever happened to hold the connection. So a
 * recompute publishes on {@code reco:acct:<id>} and every instance's subscriber relays it to the emitters it holds
 * (A-87). Redis is already in the stack and already has a per-service ACL; the channel namespace is the one thing
 * that had to be opened up for it.
 *
 * <p>Delivery is best-effort by design: SSE is a notification that something changed, and the client re-reads
 * {@code GET /v1/recommendations} (or uses the payload) to act. A dropped push is corrected by the next one, by
 * the 25-second heartbeat failing, or by the reconnect replaying the latest result — which is what
 * {@code Last-Event-ID} is for here.
 */
public final class StreamService {
  private static final Logger log = LoggerFactory.getLogger(StreamService.class);
  private static final SecureRandom RANDOM = new SecureRandom();

  /** One-time tickets live under this prefix; the ACL's {@code ~reco:*} covers it. */
  public static final String TICKET_PREFIX = "reco:ticket:";
  /** The fan-out channel for one account. Inside the service's own {@code reco:*} channel namespace (A-87). */
  public static final String CHANNEL_PREFIX = "reco:acct:";
  /**
   * The pattern an instance subscribes to. It is the service's whole namespace rather than the narrower
   * {@code reco:acct:*} that would read more naturally, because Redis checks a {@code PSUBSCRIBE} pattern against
   * an ACL's channel patterns by <em>literal equality</em>, not by glob — so with {@code &reco:*} granted,
   * {@code PSUBSCRIBE reco:acct:*} is refused outright ("no permissions to access the channel"). Subscribing to
   * the granted pattern and filtering in {@link #onMessage} costs nothing: the only channels reco publishes on are
   * {@code reco:acct:<id>}, and tickets are keys, not channels.
   */
  public static final String CHANNEL_PATTERN = "reco:*";
  /** MASTER_PLAN P15: a 60-second ticket. */
  public static final Duration TICKET_TTL = Duration.ofSeconds(60);
  /** MASTER_PLAN P15: a heartbeat every 25 seconds, under the usual 30-second proxy idle timeout. */
  public static final Duration HEARTBEAT = Duration.ofSeconds(25);

  /**
   * Consume a ticket: return its value and delete it, in one round trip so two racing readers cannot both win.
   * {@code GETDEL} would be simpler but is not in the service ACL's command list; {@code get} and {@code unlink}
   * are, and a script is how dexter-cache does every other compound operation.
   */
  private static final RedisScript<String> CONSUME = new DefaultRedisScript<>(
      "local v = redis.call('get', KEYS[1]); if v then redis.call('unlink', KEYS[1]); end; return v", String.class);

  private final StringRedisTemplate redis;
  private final RecoStore store;
  private final ObjectMapper mapper;
  /** account id -> the emitters this instance is holding for it. */
  private final Map<UUID, List<Sink>> sinks = new ConcurrentHashMap<>();

  /**
   * No clock: a ticket's life is Redis's to keep (the key carries its own TTL) and an event's identity is the
   * state hash, not a timestamp — so there is nothing here for this service to read the time for.
   */
  public StreamService(StringRedisTemplate redis, RecoStore store, ObjectMapper mapper) {
    this.redis = redis;
    this.store = store;
    this.mapper = mapper;
  }

  /**
   * One open stream, as the controller's transport sees it. Keeping the SSE type out of this class is what lets
   * the service be tested with two app contexts and no HTTP (the plan's cross-instance test).
   */
  public interface Sink {
    /** Sends one event; returns false when the connection is gone and the sink should be forgotten. */
    boolean send(String eventId, String eventName, String json);

    /** Closes the connection. */
    void close();
  }

  /** What a ticket grants. */
  public record Ticket(String ticket, long expiresInSeconds) {}

  // ---------------------------------------------------------------------------------------------- tickets

  /** Issues a one-time ticket for this account. */
  public Ticket issue(Tenant tenant) {
    byte[] raw = new byte[32];
    RANDOM.nextBytes(raw);
    String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    redis.opsForValue().set(TICKET_PREFIX + ticket, tenant.accountId().toString(), TICKET_TTL);
    return new Ticket(ticket, TICKET_TTL.toSeconds());
  }

  /**
   * Consumes a ticket and answers whose it was. A second attempt with the same ticket fails, which is the plan's
   * "a ticket can be used once only".
   */
  public UUID consume(String ticket) {
    if (ticket == null || ticket.isBlank() || ticket.length() > 128) {
      throw new DexterException(RecoErrors.STREAM_TICKET_INVALID, "No usable ticket");
    }
    String account = redis.execute(CONSUME, List.of(TICKET_PREFIX + ticket));
    if (account == null) {
      throw new DexterException(RecoErrors.STREAM_TICKET_INVALID, "Ticket expired or already used");
    }
    try {
      return UUID.fromString(account);
    } catch (IllegalArgumentException e) {
      throw new DexterException(RecoErrors.STREAM_TICKET_INVALID, "Ticket is malformed");
    }
  }

  // ---------------------------------------------------------------------------------------------- streaming

  /**
   * Registers an open stream for an account and sends it the current result straight away, so a client never has
   * to wait for the next recompute to see anything.
   *
   * @param lastEventId the client's {@code Last-Event-ID}, which is the state hash it last saw. The opening event
   *     is sent regardless — a reconnect that already has the latest result gets it again, which is cheaper and
   *     safer than deciding on the client's behalf that it is still current
   */
  public void open(UUID account, Sink sink, String lastEventId) {
    sinks.computeIfAbsent(account, a -> new CopyOnWriteArrayList<>()).add(sink);
    Optional<RecoStore.ResultRow> stored = store.result(account);
    if (stored.isPresent()) {
      RecoStore.ResultRow r = stored.get();
      String name = r.stateHash().equals(lastEventId) ? "recommendation.unchanged" : "recommendation.updated";
      if (!sink.send(r.stateHash(), name, r.payloadJson())) {
        forget(account, sink);
      }
    } else {
      // nothing computed yet: say so rather than leaving the client staring at an open socket
      if (!sink.send(null, "recommendation.none", "{}")) {
        forget(account, sink);
      }
    }
  }

  /** Forgets a closed stream. */
  public void close(UUID account, Sink sink) {
    forget(account, sink);
  }

  /** How many streams this instance holds for an account, for tests and the trail page. */
  public int openStreams(UUID account) {
    List<Sink> list = sinks.get(account);
    return list == null ? 0 : list.size();
  }

  /**
   * Publishes one account's new result to every instance. Called after the recompute has committed, so a listener
   * that immediately re-reads cannot see the old row.
   */
  public void publish(UUID account, String stateHash, String payloadJson) {
    try {
      redis.convertAndSend(CHANNEL_PREFIX + account,
          mapper.writeValueAsString(Map.of("accountId", account.toString(), "stateHash", stateHash,
              "payload", payloadJson)));
    } catch (RuntimeException e) {
      // a stream is a notification, never the system of record: the stored result is already committed
      log.warn("RECO_STREAM_PUBLISH_FAILED account={} error={}", account, e.toString());
    }
  }

  /** Relays a message from the channel to the emitters this instance holds. Called by the Redis listener. */
  public void onMessage(String channel, String body) {
    if (channel == null || !channel.startsWith(CHANNEL_PREFIX)) {
      return;
    }
    UUID account;
    try {
      account = UUID.fromString(channel.substring(CHANNEL_PREFIX.length()));
    } catch (IllegalArgumentException e) {
      return;
    }
    List<Sink> list = sinks.get(account);
    if (list == null || list.isEmpty()) {
      return;
    }
    String hash;
    String payload;
    try {
      var node = mapper.readTree(body);
      hash = node.get("stateHash").asString();
      payload = node.get("payload").asString();
    } catch (RuntimeException e) {
      log.warn("RECO_STREAM_BAD_MESSAGE channel={} error={}", channel, e.toString());
      return;
    }
    for (Sink s : list) {
      if (!s.send(hash, "recommendation.updated", payload)) {
        forget(account, s);
      }
    }
  }

  /** The heartbeat, run on a schedule: a comment frame keeps proxies from closing an idle stream. */
  public void heartbeat() {
    sinks.forEach((account, list) -> {
      for (Sink s : list) {
        if (!s.send(null, "heartbeat", "{}")) {
          forget(account, s);
        }
      }
    });
  }

  private void forget(UUID account, Sink sink) {
    List<Sink> list = sinks.get(account);
    if (list != null) {
      list.remove(sink);
      if (list.isEmpty()) {
        sinks.remove(account, list);
      }
    }
    try {
      sink.close();
    } catch (RuntimeException ignored) {
      // closing a dead connection is allowed to fail
    }
  }
}
