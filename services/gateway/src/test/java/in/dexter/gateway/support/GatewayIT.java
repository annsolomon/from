package in.dexter.gateway.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** The normal gateway test context: live Redis (ACL user {@code gateway}) and Kafka. */
public abstract class GatewayIT extends GatewayBase {
  @DynamicPropertySource
  static void liveInfra(DynamicPropertyRegistry r) {
    r.add("spring.data.redis.host", REDIS::getHost);
    r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    r.add("spring.data.redis.password", () -> REDIS_PW);
    r.add("spring.data.redis.timeout", () -> "2s");
    r.add("dexter.gateway.activity.bootstrap-servers", KAFKA::getBootstrapServers);
  }
}
