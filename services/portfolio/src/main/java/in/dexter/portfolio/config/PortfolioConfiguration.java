package in.dexter.portfolio.config;

import in.dexter.core.time.Clocks;
import in.dexter.db.page.KeysetPager;
import in.dexter.db.tenant.TenantTx;
import in.dexter.events.InboxGuard;
import in.dexter.events.OutboxWriter;
import in.dexter.idempotency.IdempotencyEngine;
import in.dexter.portfolio.app.CardService;
import in.dexter.portfolio.app.CardStore;
import in.dexter.portfolio.app.EmiService;
import in.dexter.portfolio.app.EmiStore;
import in.dexter.portfolio.app.LinkService;
import in.dexter.portfolio.app.LinkStore;
import in.dexter.portfolio.app.Matcher;
import in.dexter.portfolio.app.ProjectionService;
import in.dexter.portfolio.app.ProjectionStore;
import in.dexter.portfolio.app.ReportService;
import in.dexter.portfolio.app.RewardService;
import in.dexter.portfolio.app.RewardStore;
import in.dexter.portfolio.app.TransactionService;
import in.dexter.portfolio.app.TransactionStore;
import in.dexter.portfolio.infra.CardRepository;
import in.dexter.portfolio.infra.EmiRepository;
import in.dexter.portfolio.infra.LinkRepository;
import in.dexter.portfolio.infra.RewardRepository;
import in.dexter.portfolio.infra.TransactionRepository;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wires portfolio (MASTER_PLAN 2.5: config wires, app/infra stay framework-light). TenantTx, KeysetPager,
 * IdempotencyEngine, OutboxWriter and the JWT interceptor come from the platform libraries' auto-configuration.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PortfolioProperties.class)
public class PortfolioConfiguration {
  static final String SCHEMA = "portfolio";

  @Bean
  Clock portfolioClock() {
    return Clocks.systemUtc();
  }

  @Bean
  CardRepository cardRepository(JdbcClient jdbc, KeysetPager pager) {
    return new CardRepository(jdbc, pager);
  }

  @Bean
  TransactionStore transactionStore(JdbcClient jdbc, KeysetPager pager) {
    return new TransactionRepository(jdbc, pager);
  }

  @Bean
  LinkStore linkStore(JdbcClient jdbc, KeysetPager pager) {
    return new LinkRepository(jdbc, pager);
  }

  @Bean
  EmiStore emiStore(JdbcClient jdbc, KeysetPager pager) {
    return new EmiRepository(jdbc, pager);
  }

  @Bean
  RewardStore rewardStore(JdbcClient jdbc, KeysetPager pager) {
    return new RewardRepository(jdbc, pager);
  }

  @Bean
  Matcher matcher(TransactionStore txns, LinkStore links) {
    return new Matcher(txns, links);
  }

  @Bean
  CardService cardService(TenantTx tx, IdempotencyEngine idempotency, CardRepository cards, OutboxWriter outbox,
      JsonMapper mapper, Clock clock) {
    return new CardService(tx, idempotency, cards, outbox, mapper, clock);
  }

  @Bean
  TransactionService transactionService(TenantTx tx, IdempotencyEngine idempotency, CardRepository cards,
      TransactionStore txns, RewardStore rewards, Matcher matcher, OutboxWriter outbox, JsonMapper mapper,
      Clock clock) {
    return new TransactionService(tx, idempotency, cards, txns, rewards, matcher, outbox, mapper, clock);
  }

  @Bean
  LinkService linkService(TenantTx tx, LinkStore links, TransactionStore txns, OutboxWriter outbox, Clock clock) {
    return new LinkService(tx, links, txns, outbox, clock);
  }

  @Bean
  EmiService emiService(TenantTx tx, IdempotencyEngine idempotency, CardRepository cards, TransactionStore txns,
      EmiStore plans, JsonMapper mapper, Clock clock) {
    return new EmiService(tx, idempotency, cards, txns, plans, mapper, clock);
  }

  @Bean
  RewardService rewardService(TenantTx tx, IdempotencyEngine idempotency, CardRepository cards, RewardStore rewards,
      JsonMapper mapper, Clock clock) {
    return new RewardService(tx, idempotency, cards, rewards, mapper, clock);
  }

  @Bean
  ReportService reportService(TenantTx tx, CardRepository cards, TransactionStore txns, Clock clock) {
    return new ReportService(tx, cards, txns, clock);
  }

  @Bean
  ProjectionService projectionService(TenantTx tx, InboxGuard inbox, CardRepository projections, LinkStore links,
      Matcher matcher, JsonMapper mapper, Clock clock) {
    return new ProjectionService(tx, inbox, projections, links, matcher, mapper, clock);
  }

  @Bean
  StatementCloserJob statementCloserJob(TransactionService txns, PortfolioProperties p) {
    return new StatementCloserJob(txns, p.closer().enabled(), p.closer().interval(), p.closer().batch());
  }
}
