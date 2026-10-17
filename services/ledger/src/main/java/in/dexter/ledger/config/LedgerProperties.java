package in.dexter.ledger.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** {@code dexter.ledger.*}. */
@ConfigurationProperties("dexter.ledger")
public record LedgerProperties(@DefaultValue Relay relay) {

  /** Outbox relay (MASTER_PLAN 6.3) as ledger_relay, plus topic creation and the idempotency-key purge. */
  public record Relay(@DefaultValue("false") boolean enabled, String url, String username, String password,
      String bootstrapServers, @DefaultValue("3") int partitions, @DefaultValue("1") short replication) {}
}
