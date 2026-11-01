package in.dexter.reco.config;

import in.dexter.core.time.Clocks;
import in.dexter.db.tenant.TenantTx;
import in.dexter.events.InboxGuard;
import in.dexter.events.OutboxWriter;
import in.dexter.reco.app.BestCardService;
import in.dexter.reco.app.ProjectionService;
import in.dexter.reco.app.RecoStore;
import in.dexter.reco.app.RecommendationService;
import in.dexter.reco.app.StreamService;
import in.dexter.reco.app.Sweeper;
import in.dexter.reco.app.WalletService;
import in.dexter.reco.infra.RecoRepository;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wires reco (MASTER_PLAN 2.5: config wires, app/infra stay framework-light). TenantTx, InboxGuard, OutboxWriter
 * and the JWT interceptor come from the platform libraries' auto-configuration.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RecoProperties.class)
public class RecoConfiguration {
  static final String SCHEMA = "reco";

  @Bean
  Clock recoClock() {
    return Clocks.systemUtc();
  }

  @Bean
  RecoStore recoStore(JdbcClient jdbc) {
    return new RecoRepository(jdbc);
  }

  /**
   * The ranking. The stream is told about a changed result through a one-method listener rather than being wired
   * in directly, so a push cannot happen inside the write transaction and a push that throws cannot undo a
   * stored result (ADR-0021 §11).
   */
  @Bean
  RecommendationService recommendationService(TenantTx tx, RecoStore store, OutboxWriter outbox, JsonMapper mapper,
      Clock clock, RecoProperties props, StreamService streams) {
    return new RecommendationService(tx, store, outbox, mapper, clock, props.scoring().savingsWeightBps(),
        props.scoring().thresholdBps(), streams::publish);
  }

  @Bean
  WalletService walletService(TenantTx tx, RecommendationService recommendations) {
    return new WalletService(tx, recommendations);
  }

  @Bean
  BestCardService bestCardService(TenantTx tx, RecoStore store, JsonMapper mapper, Clock clock) {
    return new BestCardService(tx, store, mapper, clock);
  }

  /** The four projections: ledger spend (and per-card spend, A-86), held cards, catalog rules, offers. */
  @Bean
  ProjectionService projectionService(TenantTx tx, InboxGuard inbox, RecoStore store, JsonMapper mapper,
      Clock clock) {
    return new ProjectionService(tx, inbox, store, mapper, clock);
  }

  @Bean
  Sweeper sweeper(TenantTx tx, RecoStore store, RecommendationService recommendations, Clock clock,
      RecoProperties props) {
    return new Sweeper(tx, store, recommendations, clock, props.sweeper().debounce(), props.sweeper().batch());
  }
}
