package in.dexter.audit.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import in.dexter.audit.app.ActivityService;
import in.dexter.audit.app.AuditLogService;
import in.dexter.audit.app.EventIngest;
import in.dexter.audit.app.ExportStore;
import in.dexter.audit.app.NotificationSender;
import in.dexter.audit.app.NotificationService;
import in.dexter.audit.app.PrivacyService;
import in.dexter.audit.domain.Backoff;
import in.dexter.audit.domain.SubjectRef;
import in.dexter.audit.infra.ActivityRepository;
import in.dexter.audit.infra.AuditRepository;
import in.dexter.audit.infra.MockNotificationSender;
import in.dexter.audit.infra.NotificationRepository;
import in.dexter.audit.infra.PrivacyRepository;
import in.dexter.audit.infra.S3ExportStore;
import in.dexter.core.time.Clocks;
import in.dexter.db.page.KeysetPager;
import in.dexter.db.tenant.TenantTx;
import in.dexter.events.InboxGuard;
import in.dexter.events.OutboxWriter;
import in.dexter.idempotency.IdempotencyEngine;
import in.dexter.objectstore.S3Objects;
import in.dexter.objectstore.SigV4;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wires audit (MASTER_PLAN 2.5). Two pools: the auto-configured one as audit_app (users and the SYSTEM actor) and
 * {@link AdminPool} as audit_admin (operators) with its own TenantTx and repositories — not a DataSource bean
 * (ADR-0010). TenantTx, KeysetPager, IdempotencyEngine, OutboxWriter, InboxGuard and the JWT verifier come from the
 * platform libraries.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuditProperties.class)
public class AuditConfiguration {

  /** The audit_admin pool with its own transaction manager, TenantTx and repositories. */
  public static final class AdminPool implements AutoCloseable {
    final HikariDataSource dataSource;
    final TenantTx tx;
    final AuditRepository audit;
    final ActivityRepository activity;
    final PrivacyRepository privacy;

    AdminPool(HikariDataSource dataSource, KeysetPager pager, JsonMapper mapper) {
      this.dataSource = dataSource;
      JdbcClient jdbc = JdbcClient.create(dataSource);
      this.tx = new TenantTx(new JdbcTransactionManager(dataSource), jdbc);
      this.audit = new AuditRepository(jdbc, pager, mapper);
      this.activity = new ActivityRepository(jdbc);
      this.privacy = new PrivacyRepository(jdbc, pager);
    }

    @Override
    public void close() {
      dataSource.close();
    }
  }

  @Bean
  Clock auditClock() {
    return Clocks.systemUtc();
  }

  @Bean(destroyMethod = "close")
  AdminPool auditAdminPool(AuditProperties p, Environment env, KeysetPager pager, JsonMapper mapper) {
    AuditProperties.Admin a = p.admin();
    HikariConfig c = new HikariConfig();
    c.setPoolName("audit-admin");
    c.setJdbcUrl(a.dbUrl() != null && !a.dbUrl().isBlank() ? a.dbUrl() : env.getRequiredProperty("spring.datasource.url"));
    c.setUsername(a.dbUsername());
    c.setPassword(a.dbPassword());
    c.setMaximumPoolSize(a.dbPoolSize());
    c.setMinimumIdle(0);
    c.setConnectionTimeout(Duration.ofSeconds(5).toMillis());
    c.setInitializationFailTimeout(-1);
    return new AdminPool(new HikariDataSource(c), pager, mapper);
  }

  @Bean
  AuditRepository auditRepository(JdbcClient jdbc, KeysetPager pager, JsonMapper mapper) {
    return new AuditRepository(jdbc, pager, mapper);
  }

  @Bean
  ActivityRepository activityRepository(JdbcClient jdbc) {
    return new ActivityRepository(jdbc);
  }

  @Bean
  NotificationRepository notificationRepository(JdbcClient jdbc, KeysetPager pager, JsonMapper mapper) {
    return new NotificationRepository(jdbc, pager, mapper);
  }

  @Bean
  PrivacyRepository privacyRepository(JdbcClient jdbc, KeysetPager pager) {
    return new PrivacyRepository(jdbc, pager);
  }

  /** The object store (tests add an in-memory one as @Primary). */
  @Bean
  ExportStore exportStore(AuditProperties p, Clock clock) {
    AuditProperties.Store s = p.store();
    if (s.endpoint() == null || s.endpoint().isBlank()) {
      throw new IllegalStateException("dexter.audit.store.endpoint is required");
    }
    return new S3ExportStore(new S3Objects(new SigV4(s.accessKey() == null ? "" : s.accessKey(),
        s.secretKey() == null ? "" : s.secretKey(), s.region()), s.endpoint(), s.publicEndpoint(), s.bucket(), clock));
  }

  /** The mock transport (tests add a controllable one as @Primary). */
  @Bean
  NotificationSender notificationSender(AuditProperties p) {
    return new MockNotificationSender(p.notifications().mockFailFirst());
  }

  @Bean
  SubjectRef subjectRef(AuditProperties p) {
    String key = p.privacy().subjectKey();
    if (key == null || key.isBlank()) {
      throw new IllegalStateException("dexter.audit.privacy.subject-key (AUDIT_SUBJECT_KEY) is required");
    }
    return new SubjectRef(Base64.getDecoder().decode(key.strip()));
  }

  @Bean
  AuditLogService auditLogService(AdminPool pool, TenantTx tx, AuditRepository audit, ExportStore exports,
      JsonMapper mapper, Clock clock) {
    return new AuditLogService(pool.tx, pool.audit, tx, audit, exports, mapper, clock);
  }

  @Bean
  ActivityService activityService(TenantTx tx, ActivityRepository activity, AdminPool pool, Clock clock) {
    return new ActivityService(tx, activity, pool.tx, pool.activity, clock);
  }

  @Bean
  NotificationService notificationService(TenantTx tx, NotificationRepository store, NotificationSender sender,
      AuditProperties p, Clock clock) {
    AuditProperties.Notify n = p.notifications();
    return new NotificationService(tx, store, sender, new Backoff(n.backoffBase(), n.backoffCap(), n.maxAttempts()),
        clock);
  }

  @Bean
  PrivacyService privacyService(TenantTx tx, PrivacyRepository store, AuditRepository audit,
      NotificationService notifications, IdempotencyEngine idempotency, OutboxWriter outbox, InboxGuard inbox,
      ExportStore exports, SubjectRef subjects, JsonMapper mapper, Clock clock, AuditProperties p, AdminPool pool) {
    AuditProperties.Privacy s = p.privacy();
    return new PrivacyService(tx, store, audit, notifications, idempotency, outbox, inbox, exports, subjects, mapper,
        clock, new PrivacyService.Settings(s.services(), s.phaseTimeout(), s.maxAttempts()), pool.tx, pool.privacy);
  }

  @Bean
  EventIngest eventIngest(TenantTx tx, InboxGuard inbox, AuditRepository audit, NotificationService notifications,
      ActivityService activity, JsonMapper mapper) {
    return new EventIngest(tx, inbox, audit, notifications, activity, mapper);
  }

  @Bean
  AuditJobs auditJobs(NotificationService notifications, PrivacyService privacy, AuditLogService log,
      AuditProperties p) {
    return new AuditJobs(notifications, privacy, log, p.jobs());
  }
}
