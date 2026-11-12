package in.dexter.reco.config;

import in.dexter.db.tenant.TenantTx;
import in.dexter.reco.app.RecoStore;
import in.dexter.reco.app.StreamService;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * The SSE wiring (MASTER_PLAN P15 step 3), in its own configuration because it is the one part of reco built on
 * Redis — the ticket store and the cross-instance channel.
 *
 * <p>It is <b>not</b> conditional on a {@link StringRedisTemplate} being present, and the first version's attempt
 * to make it so is worth recording: {@code @ConditionalOnBean} on an ordinary {@code @Configuration} is evaluated
 * while user configuration is being scanned, which is <em>before</em> auto-configuration has run — so the
 * template Boot was about to create did not exist yet, the condition failed, and {@code StreamController} came up
 * with no {@code StreamService} to inject. That condition is only dependable inside an {@code @AutoConfiguration}
 * class. Reco declares {@code spring-boot-starter-data-redis} and configures {@code spring.data.redis.*}, so the
 * template is always there; Lettuce connects lazily, so a Redis that is down costs the stream, not the service.
 *
 * <p>Two moving parts beyond the service itself. The <b>listener container</b> subscribes this instance to
 * {@link StreamService#CHANNEL_PATTERN} and relays every message to the emitters it holds — a pattern
 * subscription rather than one per account, because an instance's set of streams changes constantly and
 * re-subscribing on each open would be a round trip per viewer. The <b>heartbeat</b> sends a comment frame every 25 seconds, which keeps proxies from
 * closing an idle stream and is also how a dead connection is noticed and forgotten.
 */
@Configuration(proxyBeanMethods = false)
public class RecoStreamConfiguration {
  private static final Logger log = LoggerFactory.getLogger(RecoStreamConfiguration.class);

  @Bean
  StreamService streamService(StringRedisTemplate redis, TenantTx tx, RecoStore store, JsonMapper mapper) {
    return new StreamService(redis, tx, store, mapper);
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
    }, new PatternTopic(StreamService.CHANNEL_PATTERN));
    // started by recoStreamListenerStarter, never by the context: subscribing blocks on Redis, and a slow or absent
    // Redis must cost the stream, not the service's start (P18 r6: a 200 ms handshake under a loaded CI host)
    container.setAutoStartup(false);
    return container;
  }

  /**
   * Starts the listener container off the startup path and keeps trying (every 2 s) until it is subscribed, so a
   * Redis that is slow or down at boot delays the stream instead of failing the application.
   */
  @Bean
  SmartLifecycle recoStreamListenerStarter(RedisMessageListenerContainer container) {
    return new SmartLifecycle() {
      private volatile boolean running;
      private Thread thread;

      @Override
      public synchronized void start() {
        running = true;
        thread = Thread.ofPlatform().daemon().name("reco-stream-subscribe").start(() -> {
          while (running && !container.isRunning()) {
            try {
              container.start();
              log.info("RECO_STREAM_SUBSCRIBED pattern={}", StreamService.CHANNEL_PATTERN);
            } catch (RuntimeException e) {
              log.warn("RECO_STREAM_SUBSCRIBE_RETRY error={}", e.toString());
              try {
                Thread.sleep(2_000);
              } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
              }
            }
          }
        });
      }

      @Override
      public synchronized void stop() {
        running = false;
        if (thread != null) {
          thread.interrupt();
        }
        if (container.isRunning()) {
          container.stop();
        }
      }

      @Override
      public boolean isRunning() {
        return running;
      }
    };
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
