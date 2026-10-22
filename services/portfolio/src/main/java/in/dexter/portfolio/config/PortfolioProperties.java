package in.dexter.portfolio.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** {@code dexter.portfolio.*}. */
@ConfigurationProperties("dexter.portfolio")
public record PortfolioProperties(@DefaultValue Relay relay, @DefaultValue Consumers consumers,
    @DefaultValue Closer closer) {

  /** Outbox relay (MASTER_PLAN 6.3) as portfolio_relay, plus topic creation and the idempotency-key purge. */
  public record Relay(@DefaultValue("false") boolean enabled, String url, String username, String password,
      String bootstrapServers, @DefaultValue("3") int partitions, @DefaultValue("1") short replication) {}

  /** The event consumers: catalog projection and ledger expense matching (MASTER_PLAN 6.2 retries). */
  public record Consumers(@DefaultValue("false") boolean enabled, String bootstrapServers) {}

  /** The statement closer job (generates ended cycles). */
  public record Closer(@DefaultValue("true") boolean enabled, @DefaultValue("10m") Duration interval,
      @DefaultValue("500") int batch) {}
}
