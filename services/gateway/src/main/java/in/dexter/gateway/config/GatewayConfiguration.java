package in.dexter.gateway.config;

import in.dexter.core.time.Clocks;
import in.dexter.gateway.domain.RouteClass;
import in.dexter.gateway.domain.RouteTable;
import in.dexter.gateway.domain.ServiceRoute;
import in.dexter.gateway.infra.ActivityFilter;
import in.dexter.gateway.infra.ActivityPublisher;
import in.dexter.gateway.infra.BodySizeFilter;
import in.dexter.gateway.infra.ClientIp;
import in.dexter.gateway.infra.GatewayErrorHandler;
import in.dexter.gateway.infra.JwtPreCheckFilter;
import in.dexter.gateway.infra.OrderedCorsFilter;
import in.dexter.gateway.infra.Problems;
import in.dexter.gateway.infra.RateLimitFilter;
import in.dexter.gateway.infra.RedisTokenBucket;
import in.dexter.gateway.infra.RequestIdFilter;
import in.dexter.gateway.infra.RouteMatcher;
import in.dexter.gateway.infra.SecurityHeadersFilter;
import in.dexter.gateway.infra.StripIdentityHeadersFilter;
import in.dexter.security.jwt.JwtVerifier;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.circuitbreaker.resilience4j.ReactiveResilience4JCircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigBuilder;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.cloud.gateway.support.RouteMetadataUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wires the gateway (MASTER_PLAN P06): the route table as Spring Cloud Gateway routes (one per service, multi-path
 * predicates, circuit breaker per route except SSE, per-route timeouts), the edge filters in their fixed order
 * ({@code FilterOrder}), the Redis rate limiter, activity events, CORS and the ProblemDetail error handler.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GatewayProperties.class)
public class GatewayConfiguration {
  private static final Logger log = LoggerFactory.getLogger(GatewayConfiguration.class);

  @Bean
  Clock gatewayClock() {
    return Clocks.systemUtc();
  }

  @Bean
  RouteMatcher routeMatcher(GatewayProperties p) {
    return RouteMatcher.standard(p.devRoutes());
  }

  @Bean
  Problems problems(JsonMapper json) {
    return new Problems(json);
  }

  // ------------------------------------------------------------------------------------------- routes

  @Bean
  RouteLocator dexterRoutes(RouteLocatorBuilder builder, GatewayProperties p) {
    List<ServiceRoute> table = new ArrayList<>(RouteTable.ROUTES);
    if (p.devRoutes()) {
      table.add(RouteTable.DEV_IDENTITY);
    }
    RouteLocatorBuilder.Builder routes = builder.routes();
    long connectMs = p.connectTimeout().toMillis();
    long responseMs = p.responseTimeout().toMillis();
    int order = 0;
    for (ServiceRoute r : table) {
      String uri = p.services().get(r.service());
      if (uri == null || uri.isBlank()) {
        throw new IllegalStateException("dexter.gateway.services." + r.service() + " is not set");
      }
      int o = order++;
      String[] paths = r.patterns().toArray(String[]::new);
      if (r.sse()) {
        // SSE: no circuit breaker, no time limiter, no response timeout (MASTER_PLAN P06 step 3)
        routes.route(r.id(), s -> s.order(o).path(paths)
            .metadata(RouteMetadataUtils.CONNECT_TIMEOUT_ATTR, (int) connectMs)
            .metadata(RouteMetadataUtils.RESPONSE_TIMEOUT_ATTR, -1)
            .uri(uri));
      } else {
        routes.route(r.id(), s -> s.order(o).path(paths)
            .filters(f -> f.circuitBreaker(c -> c.setName(r.service())))
            .metadata(RouteMetadataUtils.CONNECT_TIMEOUT_ATTR, (int) connectMs)
            .metadata(RouteMetadataUtils.RESPONSE_TIMEOUT_ATTR, responseMs)
            .uri(uri));
      }
    }
    log.info("GATEWAY_ROUTES routes={} services={}", table.size(), p.services().keySet());
    return routes.build();
  }

  /**
   * Circuit breaker defaults for every route. The time limiter sits above the route's response timeout, so the
   * Netty timeout (504) fires first and the breaker only counts it.
   */
  @Bean
  Customizer<ReactiveResilience4JCircuitBreakerFactory> breakerDefaults(GatewayProperties p) {
    GatewayProperties.Breaker b = p.breaker();
    return factory -> factory.configureDefault(id -> new Resilience4JConfigBuilder(id)
        .circuitBreakerConfig(CircuitBreakerConfig.custom()
            .failureRateThreshold(b.failureRatePercent())
            .slidingWindowSize(b.slidingWindow())
            .minimumNumberOfCalls(b.minimumCalls())
            .waitDurationInOpenState(b.waitOpen())
            .build())
        .timeLimiterConfig(TimeLimiterConfig.custom().timeoutDuration(p.responseTimeout().plusSeconds(5)).build())
        .build());
  }

  // ------------------------------------------------------------------------------------------- filters (order: FilterOrder)

  @Bean
  RequestIdFilter requestIdFilter() {
    return new RequestIdFilter();
  }

  @Bean
  SecurityHeadersFilter securityHeadersFilter(GatewayProperties p) {
    return new SecurityHeadersFilter(p.headers().hsts());
  }

  @Bean
  StripIdentityHeadersFilter stripIdentityHeadersFilter(GatewayProperties p) {
    return new StripIdentityHeadersFilter(new ClientIp(p.trustedProxies()));
  }

  @Bean
  OrderedCorsFilter corsFilter(GatewayProperties p) {
    return new OrderedCorsFilter(p.cors().allowedOrigins(), p.cors().maxAge());
  }

  @Bean
  BodySizeFilter bodySizeFilter(GatewayProperties p, Problems problems) {
    return new BodySizeFilter(p.maxBody().toBytes(), problems);
  }

  @Bean
  JwtPreCheckFilter jwtPreCheckFilter(JwtVerifier verifier, RouteMatcher routes, Problems problems) {
    return new JwtPreCheckFilter(verifier, routes, problems);
  }

  @Bean
  @ConditionalOnProperty(prefix = "dexter.gateway.rate-limit", name = "enabled", havingValue = "true",
      matchIfMissing = true)
  RateLimitFilter rateLimitFilter(ReactiveStringRedisTemplate redis, GatewayProperties p, Problems problems,
      Clock clock) {
    GatewayProperties.RateLimit rl = p.rateLimit();
    Map<RouteClass, Integer> limits = new EnumMap<>(RouteClass.class);
    limits.put(RouteClass.AUTH, rl.auth());
    limits.put(RouteClass.WRITE, rl.write());
    limits.put(RouteClass.READ, rl.read());
    limits.put(RouteClass.CALC, rl.calc());
    return new RateLimitFilter(new RedisTokenBucket(redis, Duration.ofMillis(200)), limits, problems, clock);
  }

  @Bean(destroyMethod = "close")
  @ConditionalOnProperty(prefix = "dexter.gateway.activity", name = "enabled", havingValue = "true",
      matchIfMissing = true)
  ActivityPublisher activityPublisher(GatewayProperties p, JsonMapper json) {
    GatewayProperties.Activity a = p.activity();
    if (a.bootstrapServers() == null || a.bootstrapServers().isBlank()) {
      throw new IllegalStateException("dexter.gateway.activity.bootstrap-servers is required (or enabled=false)");
    }
    return new ActivityPublisher(a.bootstrapServers(), a.topic(), a.partitions(), a.replication(), a.queueCapacity(),
        json);
  }

  @Bean
  @ConditionalOnProperty(prefix = "dexter.gateway.activity", name = "enabled", havingValue = "true",
      matchIfMissing = true)
  ActivityFilter activityFilter(ActivityPublisher publisher, GatewayProperties p) {
    return new ActivityFilter(publisher, p.activity().sampleRate());
  }

  @Bean
  GatewayErrorHandler gatewayErrorHandler(Problems problems) {
    return new GatewayErrorHandler(problems);
  }
}
