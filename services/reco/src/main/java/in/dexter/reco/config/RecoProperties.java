package in.dexter.reco.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** {@code dexter.reco.*}. */
@ConfigurationProperties("dexter.reco")
public record RecoProperties(@DefaultValue Scoring scoring, @DefaultValue Sweeper sweeper,
    @DefaultValue Relay relay, @DefaultValue Consumers consumers) {

  /**
   * The 60/40 blend, configurable as MASTER_PLAN P14 asks.
   *
   * @param savingsWeightBps how much of the blend is rupees saved; the rest is expense fit
   * @param thresholdBps below this blended score a card goes in the not-recommended bucket
   */
  public record Scoring(@DefaultValue("6000") int savingsWeightBps, @DefaultValue("2000") int thresholdBps) {}

  /**
   * The debounced recompute (MASTER_PLAN P14 step 2).
   *
   * @param debounce how quiet an account must be before it is claimed
   * @param batch how many accounts one pass claims with SKIP LOCKED
   * @param every how often a pass runs
   */
  public record Sweeper(@DefaultValue("true") boolean enabled, @DefaultValue("3s") java.time.Duration debounce,
      @DefaultValue("200") int batch, @DefaultValue("1s") java.time.Duration every,
      @DefaultValue("180d") java.time.Duration historyRetention) {}

  /** Outbox relay (MASTER_PLAN 6.3) as reco_relay, plus topic creation and the history partitions. */
  public record Relay(@DefaultValue("false") boolean enabled, String url, String username, String password,
      String bootstrapServers, @DefaultValue("3") int partitions, @DefaultValue("1") short replication) {}

  /** The three projection consumers (MASTER_PLAN 6.2 retries). */
  public record Consumers(@DefaultValue("false") boolean enabled, String bootstrapServers) {}
}
