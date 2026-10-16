package in.dexter.events.autoconfigure;

import in.dexter.events.EnvelopeCodec;
import in.dexter.events.InboxGuard;
import in.dexter.events.InboxPurgeJob;
import in.dexter.events.KafkaClients;
import in.dexter.events.OutboxWriter;
import in.dexter.events.relay.OutboxRelay;
import java.time.Clock;
import java.util.List;
import org.apache.kafka.clients.producer.Producer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wires the outbox writer and inbox guard when the service has a JdbcClient and {@code
 * dexter.db.schema}; the relay only with {@code dexter.events.relay.enabled=true}. Nothing is active
 * by default, so services without events are unaffected.
 */
@AutoConfiguration(afterName = {
    "org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration",
    "org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration"})
@EnableConfigurationProperties(EventsProperties.class)
public class DexterEventsAutoConfiguration {

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnBean({JdbcClient.class, JsonMapper.class})
  @ConditionalOnProperty(prefix = "dexter.db", name = "schema")
  static class WriterConfiguration {
    @Bean
    @ConditionalOnMissingBean
    EnvelopeCodec envelopeCodec(JsonMapper mapper) {
      return new EnvelopeCodec(mapper);
    }

    @Bean
    @ConditionalOnMissingBean
    OutboxWriter outboxWriter(JdbcClient jdbc, EnvelopeCodec codec, Environment env, ObjectProvider<Clock> clock) {
      String schema = env.getRequiredProperty("dexter.db.schema");
      return new OutboxWriter(jdbc, codec, schema, schema, clock.getIfAvailable(Clock::systemUTC), () -> null);
    }

    @Bean
    @ConditionalOnMissingBean
    InboxGuard inboxGuard(JdbcClient jdbc, Environment env) {
      return new InboxGuard(jdbc, env.getRequiredProperty("dexter.db.schema"));
    }
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnProperty(prefix = "dexter.events.relay", name = "enabled", havingValue = "true")
  static class RelayConfiguration {
    @Bean
    RelayLifecycle dexterOutboxRelay(EventsProperties props, Environment env) {
      String schema = env.getRequiredProperty("dexter.db.schema");
      EventsProperties.Relay r = props.relay();
      SimpleDriverDataSource ds = new SimpleDriverDataSource(new org.postgresql.Driver(), r.url(), r.username(),
          r.password());
      JdbcClient relayJdbc = JdbcClient.create(ds);
      JdbcTransactionManager relayTx = new JdbcTransactionManager(ds);
      Producer<String, byte[]> producer = KafkaClients.idempotentProducer(props.bootstrapServers(), schema + "-relay");
      OutboxRelay relay = new OutboxRelay(ds, producer, OutboxRelay.Settings.defaults(schema), OutboxRelay.Hooks.NONE,
          List.of(new InboxPurgeJob(relayJdbc, relayTx, schema, 200)));
      return new RelayLifecycle(relay, producer);
    }
  }
}
