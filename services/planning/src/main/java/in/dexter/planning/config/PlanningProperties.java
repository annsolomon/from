package in.dexter.planning.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** {@code dexter.planning.*}. */
@ConfigurationProperties("dexter.planning")
public record PlanningProperties(@DefaultValue Relay relay, @DefaultValue Consumers consumers) {

  /** Outbox relay (MASTER_PLAN 6.3) as planning_relay, plus topic creation and the idempotency-key purge. */
  public record Relay(@DefaultValue("false") boolean enabled, String url, String username, String password,
      String bootstrapServers, @DefaultValue("3") int partitions, @DefaultValue("1") short replication) {}

  /** The budget-spend consumer on {@code dexter.ledger.expense.v1} (MASTER_PLAN 6.2 retries). */
  public record Consumers(@DefaultValue("false") boolean enabled, String bootstrapServers) {}
}
