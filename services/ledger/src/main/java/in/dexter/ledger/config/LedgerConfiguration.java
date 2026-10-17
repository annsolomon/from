package in.dexter.ledger.config;

import in.dexter.core.time.Clocks;
import in.dexter.db.page.KeysetPager;
import in.dexter.db.tenant.TenantTx;
import in.dexter.events.OutboxWriter;
import in.dexter.idempotency.IdempotencyEngine;
import in.dexter.ledger.app.CategoryService;
import in.dexter.ledger.app.CategoryStore;
import in.dexter.ledger.app.DailyTotalsStore;
import in.dexter.ledger.app.ExpenseService;
import in.dexter.ledger.app.ExpenseStore;
import in.dexter.ledger.infra.CategoryRepository;
import in.dexter.ledger.infra.DailyTotalsRepository;
import in.dexter.ledger.infra.ExpenseRepository;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wires ledger (MASTER_PLAN 2.5: config wires, app/infra stay framework-light). TenantTx, KeysetPager,
 * IdempotencyEngine, OutboxWriter and the JWT interceptor come from the platform libraries' auto-configuration.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LedgerProperties.class)
public class LedgerConfiguration {

  @Bean
  Clock ledgerClock() {
    return Clocks.systemUtc();
  }

  @Bean
  ExpenseStore expenseStore(JdbcClient jdbc, KeysetPager pager) {
    return new ExpenseRepository(jdbc, pager);
  }

  @Bean
  DailyTotalsStore dailyTotalsStore(JdbcClient jdbc) {
    return new DailyTotalsRepository(jdbc);
  }

  @Bean
  CategoryStore categoryStore(JdbcClient jdbc) {
    return new CategoryRepository(jdbc);
  }

  @Bean
  ExpenseService expenseService(TenantTx tx, IdempotencyEngine idempotency, ExpenseStore expenses,
      DailyTotalsStore daily, CategoryStore categories, OutboxWriter outbox, JsonMapper mapper, Clock clock) {
    return new ExpenseService(tx, idempotency, expenses, daily, categories, outbox, mapper, clock);
  }

  @Bean
  CategoryService categoryService(TenantTx tx, IdempotencyEngine idempotency, CategoryStore categories,
      JsonMapper mapper) {
    return new CategoryService(tx, idempotency, categories, mapper);
  }
}
