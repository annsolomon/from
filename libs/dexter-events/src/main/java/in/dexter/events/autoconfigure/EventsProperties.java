package in.dexter.events.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code dexter.events.*}. The relay runs only when {@code relay.enabled=true}; it connects as
 * {@code <svc>_relay} with its own credentials (never the app role).
 */
@ConfigurationProperties(prefix = "dexter.events")
public record EventsProperties(String bootstrapServers, Relay relay, Topics topics) {
  public record Relay(boolean enabled, String url, String username, String password) {}

  public record Topics(Integer partitions, Short replication, Boolean create) {}
}
