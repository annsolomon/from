package in.dexter.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.gateway.support.GatewayIT;
import in.dexter.gateway.support.Tokens;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** MASTER_PLAN P06 step 4: sampled activity events reach Kafka in the standard envelope. */
class ActivityIT extends GatewayIT {
  static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void routedRequestsBecomeActivityEvents() {
    UUID account = UUID.randomUUID();
    String rid = "activity-" + UUID.randomUUID();
    assertThat(send("GET", "/v1/cards", null, auth(Tokens.user(account), "X-Request-Id", rid,
        "X-Forwarded-For", ip())).status()).isEqualTo(200);
    String anonRid = "activity-anon-" + UUID.randomUUID();
    assertThat(send("GET", "/v1/catalog/cards", null, "X-Request-Id", anonRid, "X-Forwarded-For", ip()).status())
        .isEqualTo(200);

    JsonNode mine = null;
    JsonNode anon = null;
    try (KafkaConsumer<String, byte[]> c = new KafkaConsumer<>(Map.of(
        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
        ConsumerConfig.GROUP_ID_CONFIG, "activity-it-" + UUID.randomUUID(),
        ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"), new StringDeserializer(), new ByteArrayDeserializer())) {
      c.subscribe(List.of("dexter.gateway.activity.v1"));
      long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
      while ((mine == null || anon == null) && System.nanoTime() < deadline) {
        for (ConsumerRecord<String, byte[]> rec : c.poll(Duration.ofMillis(500))) {
          JsonNode env = JSON.readTree(new String(rec.value(), StandardCharsets.UTF_8));
          String r = env.get("data").get("requestId").asString();
          if (r.equals(rid)) {
            mine = env;
            assertThat(rec.key()).isEqualTo(account.toString());
          } else if (r.equals(anonRid)) {
            anon = env;
          }
        }
      }
    }
    assertThat(mine).as("event for the authenticated request").isNotNull();
    assertThat(mine.get("type").asString()).isEqualTo("gateway.activity.recorded");
    assertThat(mine.get("version").asInt()).isEqualTo(1);
    assertThat(mine.get("source").asString()).isEqualTo("gateway");
    assertThat(mine.get("accountId").asString()).isEqualTo(account.toString());
    JsonNode d = mine.get("data");
    assertThat(d.get("service").asString()).isEqualTo("portfolio");
    assertThat(d.get("routePattern").asString()).isEqualTo("/v1/cards");
    assertThat(d.get("routeClass").asString()).isEqualTo("read");
    assertThat(d.get("status").asInt()).isEqualTo(200);
    assertThat(anon).as("event for the anonymous request").isNotNull();
    assertThat(anon.get("accountId").isNull()).isTrue();
  }
}
