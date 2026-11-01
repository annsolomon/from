package in.dexter.reco.config;

import in.dexter.reco.app.RecoStore;
import in.dexter.reco.app.StreamService;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * The SSE wiring (MASTER_PLAN P15 step 3), in its own configuration because it is the one part of reco that needs
 * Redis: with no {@link StringRedisTemplate} there is no ticket store and no cross-instance channel, so there is
 * no stream either and the rest of reco carries on unchanged.
 *
 * <p>Two moving parts beyond the service itself. The <b>listener container</b> subscribes this instance to
 * {@code reco:acct:*} and relays every message to the emitters it holds — a pattern subscription rather than one
 * per account, because an instance's set of streams changes constantly and re-subscribing on each open would be a
 * round trip per viewer. The <b>heartbeat</b> sends a comment frame every 25 seconds, which keeps proxies from
 * closing an idle stream and is also how a dead connection is noticed and forgotten.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBean(StringRedisTemplate.class)
public class RecoStreamConfiguration {
  private static final Logger log = LoggerFactory.getLogger(RecoStreamConfiguration.class);

  @Bean
  StreamService streamService(StringRedisTemplate redis, RecoStore store, JsonMapper mapper, Clock clock) {
    return new StreamService(redis, store, mapper, clock);
  }

  /** Subscribes this instance to every account's channel and hands messages to the service. */
  @Bean
  RedisMessageListenerContainer recoStreamListener(RedisConnectionFactory factory, StreamService streams) {
    RedisMessageListenerContainer container = new RedisMessageListenerContainer();
    container.setConnectionFactory(factory);
    container.addMessageListener((message, pattern) -> {
      String channel = new String(message.getChannel(), StandardCharsets.UTF_8);
      String body = new String(message.getBody(), StandardCharsets.UTF_8);
      try {
        streams.onMessage(channel, body);
      } catch (RuntimeException e) {
        // one bad message must not take the subscription down for every viewer on this instance
        log.warn("RECO_STREAM_RELAY_FAILED channel={} error={}", channel, e.toString());
      }
    }, new PatternTopic(StreamService.CHANNEL_PREFIX + "*"));
    return container;
  }

  @Bean
  StreamHeartbeat recoStreamHeartbeat(StreamService streams) {
    return new StreamHeartbeat(streams);
  }

  /** Sends the 25-second keep-alive on every open stream this instance holds. */
  public static final class StreamHeartbeat implements SmartLifecycle {
    private final StreamService streams;
    private ScheduledExecutorService executor;

    StreamHeartbeat(StreamService streams) {
      this.streams = streams;
    }

    @Override
    public synchronized void start() {
      if (executor != null) {
        return;
      }
      executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "reco-stream-heartbeat");
        t.setDaemon(true);
        return t;
      });
      long every = StreamService.HEARTBEAT.toMillis();
      executor.scheduleWithFixedDelay(() -> {
        try {
          streams.heartbeat();
        } catch (RuntimeException e) {
          log.warn("RECO_STREAM_HEARTBEAT_FAILED error={}", e.toString());
        }
      }, every, every, TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void stop() {
      if (executor != null) {
        executor.shutdownNow();
        executor = null;
      }
    }

    @Override
    public synchronized boolean isRunning() {
      return executor != null;
    }
  }
}
