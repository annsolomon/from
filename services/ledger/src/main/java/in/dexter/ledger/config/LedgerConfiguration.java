package in.dexter.ledger.config;

import in.dexter.cache.GlobalCache;
import in.dexter.core.time.Clocks;
import in.dexter.db.page.KeysetPager;
import in.dexter.db.tenant.TenantTx;
import in.dexter.events.OutboxWriter;
import in.dexter.idempotency.IdempotencyEngine;
import in.dexter.ledger.app.BulkExpenseService;
import in.dexter.ledger.app.CategoryService;
import in.dexter.ledger.app.CategoryStore;
import in.dexter.ledger.app.DailyTotalsStore;
import in.dexter.ledger.app.ExpenseService;
import in.dexter.ledger.app.ExpenseStore;
import in.dexter.ledger.app.ImportFiles;
import in.dexter.ledger.app.ImportProcessor;
import in.dexter.ledger.app.ImportService;
import in.dexter.ledger.app.ImportStore;
import in.dexter.ledger.app.InsightService;
import in.dexter.ledger.app.LiabilitySource;
import in.dexter.ledger.app.MerchantResolver;
import in.dexter.ledger.app.MerchantService;
import in.dexter.ledger.app.MerchantStore;
import in.dexter.ledger.app.ReferenceCache;
import in.dexter.ledger.app.RuleService;
import in.dexter.ledger.app.RuleStore;
import in.dexter.ledger.infra.CategoryRepository;
import in.dexter.ledger.infra.DailyTotalsRepository;
import in.dexter.ledger.infra.ExpenseRepository;
import in.dexter.ledger.infra.ImportRepository;
import in.dexter.ledger.infra.MerchantRepository;
import in.dexter.ledger.infra.RedisReferenceCache;
import in.dexter.ledger.infra.RuleRepository;
import in.dexter.ledger.infra.objectstore.S3ImportFiles;
import in.dexter.ledger.infra.objectstore.SigV4;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wires ledger (MASTER_PLAN 2.5: config wires, app/infra stay framework-light). TenantTx, KeysetPager,
 * IdempotencyEngine, OutboxWriter, GlobalCache and the JWT interceptor come from the platform libraries'
 * auto-configuration.
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
  MerchantStore merchantStore(JdbcClient jdbc) {
    return new MerchantRepository(jdbc);
  }

  @Bean
  RuleStore ruleStore(JdbcClient jdbc) {
    return new RuleRepository(jdbc);
  }

  @Bean
  ImportStore importStore(JdbcClient jdbc, DataSource dataSource) {
    return new ImportRepository(jdbc, dataSource);
  }

  /** Redis (dexter-cache) when the service has Redis configured; otherwise straight to the database. */
  @Bean
  ReferenceCache referenceCache(ObjectProvider<GlobalCache> cache) {
    GlobalCache c = cache.getIfAvailable();
    return c == null ? ReferenceCache.NONE : new RedisReferenceCache(c);
  }

  @Bean
  ImportFiles importFiles(LedgerProperties props, Clock clock) {
    LedgerProperties.Imports i = props.imports();
    return new S3ImportFiles(new SigV4(i.accessKey(), i.secretKey() == null ? "" : i.secretKey(), i.region()),
        i.endpoint(), i.publicEndpoint(), i.bucket(), clock);
  }

  @Bean
  LiabilitySource liabilitySource() {
    return LiabilitySource.NONE;
  }

  @Bean
  MerchantResolver merchantResolver(TenantTx tx, MerchantStore merchants, RuleStore rules, ReferenceCache cache) {
    return new MerchantResolver(tx, merchants, rules, cache);
  }

  @Bean
  ExpenseService expenseService(TenantTx tx, IdempotencyEngine idempotency, ExpenseStore expenses,
      DailyTotalsStore daily, CategoryStore categories, OutboxWriter outbox, MerchantResolver resolver,
      JsonMapper mapper, Clock clock) {
    return new ExpenseService(tx, idempotency, expenses, daily, categories, outbox, resolver, mapper, clock);
  }

  @Bean
  BulkExpenseService bulkExpenseService(TenantTx tx, IdempotencyEngine idempotency, ExpenseStore expenses,
      DailyTotalsStore daily, CategoryStore categories, OutboxWriter outbox, MerchantResolver resolver,
      JsonMapper mapper, Clock clock) {
    return new BulkExpenseService(tx, idempotency, expenses, daily, categories, outbox, resolver, mapper, clock);
  }

  @Bean
  CategoryService categoryService(TenantTx tx, IdempotencyEngine idempotency, CategoryStore categories,
      JsonMapper mapper) {
    return new CategoryService(tx, idempotency, categories, mapper);
  }

  @Bean
  MerchantService merchantService(TenantTx tx, MerchantStore merchants, MerchantResolver resolver,
      ReferenceCache cache) {
    return new MerchantService(tx, merchants, resolver, cache);
  }

  @Bean
  RuleService ruleService(TenantTx tx, IdempotencyEngine idempotency, RuleStore rules, CategoryStore categories,
      JsonMapper mapper) {
    return new RuleService(tx, idempotency, rules, categories, mapper);
  }

  @Bean
  InsightService insightService(TenantTx tx, DailyTotalsStore daily, ExpenseStore expenses,
      CategoryStore categories, LiabilitySource liabilities) {
    return new InsightService(tx, daily, expenses, categories, liabilities);
  }

  @Bean
  ImportService importService(TenantTx tx, IdempotencyEngine idempotency, ImportStore store, ImportFiles files,
      JsonMapper mapper, Clock clock, LedgerProperties props) {
    return new ImportService(tx, idempotency, store, files, mapper, clock, props.imports().maxBytes(),
        ImportRepository.HEADER);
  }

  @Bean
  ImportProcessor importProcessor(TenantTx tx, ImportStore store, ImportFiles files, ExpenseStore expenses,
      DailyTotalsStore daily, CategoryStore categories, OutboxWriter outbox, MerchantResolver resolver, Clock clock,
      LedgerProperties props) {
    LedgerProperties.Imports i = props.imports();
    return new ImportProcessor(tx, store, files, expenses, daily, categories, outbox, resolver, clock,
        new ImportProcessor.Settings(i.maxBytes(), i.maxRows(), i.stale(), i.maxAttempts()));
  }

  @Bean
  ImportWorker importWorker(ImportProcessor processor, LedgerProperties props) {
    return new ImportWorker(processor, props.imports().workerEnabled(), props.imports().pollInterval());
  }
}
