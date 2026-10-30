package in.dexter.planning.config;

import in.dexter.core.time.Clocks;
import in.dexter.db.page.KeysetPager;
import in.dexter.db.tenant.TenantTx;
import in.dexter.events.InboxGuard;
import in.dexter.events.OutboxWriter;
import in.dexter.idempotency.IdempotencyEngine;
import in.dexter.planning.app.BudgetService;
import in.dexter.planning.app.BudgetStore;
import in.dexter.planning.app.LiabilityService;
import in.dexter.planning.app.LiabilityStore;
import in.dexter.planning.app.ScenarioService;
import in.dexter.planning.app.ScenarioStore;
import in.dexter.planning.app.SpendService;
import in.dexter.planning.infra.BudgetRepository;
import in.dexter.planning.infra.LiabilityRepository;
import in.dexter.planning.infra.ScenarioRepository;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wires planning (MASTER_PLAN 2.5: config wires, app/infra stay framework-light). TenantTx, KeysetPager,
 * IdempotencyEngine, InboxGuard, OutboxWriter and the JWT interceptor come from the platform libraries'
 * auto-configuration.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PlanningProperties.class)
public class PlanningConfiguration {
  static final String SCHEMA = "planning";

  @Bean
  Clock planningClock() {
    return Clocks.systemUtc();
  }

  @Bean
  BudgetStore budgetStore(JdbcClient jdbc, KeysetPager pager) {
    return new BudgetRepository(jdbc, pager);
  }

  @Bean
  LiabilityStore liabilityStore(JdbcClient jdbc, KeysetPager pager) {
    return new LiabilityRepository(jdbc, pager);
  }

  @Bean
  ScenarioStore scenarioStore(JdbcClient jdbc, KeysetPager pager) {
    return new ScenarioRepository(jdbc, pager);
  }

  @Bean
  BudgetService budgetService(TenantTx tx, IdempotencyEngine idempotency, BudgetStore budgets, JsonMapper mapper,
      Clock clock) {
    return new BudgetService(tx, idempotency, budgets, mapper, clock);
  }

  @Bean
  LiabilityService liabilityService(TenantTx tx, IdempotencyEngine idempotency, LiabilityStore liabilities,
      OutboxWriter outbox, JsonMapper mapper, Clock clock) {
    return new LiabilityService(tx, idempotency, liabilities, outbox, mapper, clock);
  }

  @Bean
  ScenarioService scenarioService(TenantTx tx, IdempotencyEngine idempotency, ScenarioStore scenarios,
      BudgetStore budgets, LiabilityStore liabilities, JsonMapper mapper, Clock clock) {
    return new ScenarioService(tx, idempotency, scenarios, budgets, liabilities, mapper, clock);
  }

  /** The budget-spend projection: ledger expense events become per-period deltas and threshold alerts. */
  @Bean
  SpendService spendService(TenantTx tx, InboxGuard inbox, BudgetStore budgets, OutboxWriter outbox,
      JsonMapper mapper, Clock clock) {
    return new SpendService(tx, inbox, budgets, outbox, mapper, clock);
  }
}
