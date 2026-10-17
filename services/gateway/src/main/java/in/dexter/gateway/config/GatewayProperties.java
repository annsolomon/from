package in.dexter.gateway.config;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * {@code dexter.gateway.*} (MASTER_PLAN P06, 7.3).
 *
 * @param services       base URI per service name (the route table's targets)
 * @param devRoutes      route the trail's dev endpoints ({@code /_dev/p04/**}); dev profile only
 * @param maxBody        request body limit (256 KB; uploads use presigned URLs)
 * @param responseTimeout per-route response timeout (not for SSE)
 * @param trustedProxies proxies whose {@code X-Forwarded-For} is believed (CIDRs); the client IP is the right-most
 *                       address that is not one of them
 */
@ConfigurationProperties("dexter.gateway")
public record GatewayProperties(
    Map<String, String> services,
    @DefaultValue("false") boolean devRoutes,
    @DefaultValue("256KB") DataSize maxBody,
    @DefaultValue("2s") Duration connectTimeout,
    @DefaultValue("10s") Duration responseTimeout,
    @DefaultValue({"127.0.0.0/8", "::1/128", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16"})
    List<String> trustedProxies,
    @DefaultValue RateLimit rateLimit,
    @DefaultValue Breaker breaker,
    @DefaultValue Activity activity,
    @DefaultValue Cors cors,
    @DefaultValue Headers headers) {

  public GatewayProperties {
    services = services == null ? Map.of() : Map.copyOf(services);
  }

  /** Requests per minute per class; {@code enabled=false} turns the limiter off (never in prod). */
  public record RateLimit(
      @DefaultValue("true") boolean enabled,
      @DefaultValue("10") int auth,
      @DefaultValue("120") int write,
      @DefaultValue("600") int read,
      @DefaultValue("60") int calc) {}

  /** Resilience4j per route (not SSE): opens on failures/timeouts, half-opens after {@code waitOpen}. */
  public record Breaker(
      @DefaultValue("50") int failureRatePercent,
      @DefaultValue("20") int slidingWindow,
      @DefaultValue("10") int minimumCalls,
      @DefaultValue("30s") Duration waitOpen) {}

  /** Activity events to Kafka: sampled, asynchronous, dropped rather than ever blocking a request. */
  public record Activity(
      @DefaultValue("true") boolean enabled,
      @DefaultValue("0.1") double sampleRate,
      String bootstrapServers,
      @DefaultValue("dexter.gateway.activity.v1") String topic,
      @DefaultValue("1000") int queueCapacity,
      @DefaultValue("3") int partitions,
      @DefaultValue("1") short replication) {}

  /** CORS allowlist per profile (dev/trail: localhost only; prod: explicit origins). */
  public record Cors(List<String> allowedOrigins, @DefaultValue("1h") Duration maxAge) {
    public Cors {
      allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
    }
  }

  /** Response security headers; {@code hsts} only where TLS terminates in front of the gateway. */
  public record Headers(@DefaultValue("true") boolean hsts) {}
}
