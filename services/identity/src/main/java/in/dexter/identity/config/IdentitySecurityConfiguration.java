package in.dexter.identity.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import in.dexter.db.page.KeysetPager;
import in.dexter.db.tenant.TenantTx;
import in.dexter.events.EnvelopeCodec;
import in.dexter.events.OutboxWriter;
import in.dexter.identity.app.AccessTokens;
import in.dexter.identity.app.AccountStore;
import in.dexter.identity.app.AdminAuthService;
import in.dexter.identity.app.AdminDirectory;
import in.dexter.identity.app.AdminDirectoryService;
import in.dexter.identity.app.AdminStore;
import in.dexter.identity.app.ElevationService;
import in.dexter.identity.app.FieldCipher;
import in.dexter.identity.app.LoginThrottle;
import in.dexter.identity.app.Mailer;
import in.dexter.identity.app.MfaChallenges;
import in.dexter.identity.app.MfaService;
import in.dexter.identity.app.MfaStore;
import in.dexter.identity.app.PasswordHasher;
import in.dexter.identity.app.SecretSealer;
import in.dexter.identity.app.SecuritySettings;
import in.dexter.identity.app.Throttles;
import in.dexter.identity.app.UserSecondFactor;
import in.dexter.identity.domain.PasswordPolicy;
import in.dexter.identity.domain.ThrottlePolicy;
import in.dexter.identity.infra.AdminDirectoryRepository;
import in.dexter.identity.infra.AdminRepository;
import in.dexter.identity.infra.KekRing;
import in.dexter.identity.infra.KekSecretSealer;
import in.dexter.identity.infra.MfaRepository;
import in.dexter.identity.infra.RedisLoginThrottle;
import in.dexter.identity.infra.RedisMfaChallenges;
import java.time.Clock;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * P05 wiring: MFA, throttles (Redis), admin console, break-glass, and the second pool that connects as
 * {@code identity_admin} for admin endpoints acting on others (MASTER_PLAN 3.1). That pool is held in
 * {@link AdminPool} — deliberately not a {@code DataSource} bean, so Boot's single-DataSource auto-configuration
 * (identity_app, Flyway, TenantTx, OutboxWriter) stays as it is.
 */
@Configuration(proxyBeanMethods = false)
public class IdentitySecurityConfiguration {

  /** The identity_admin pool with its own transaction manager, TenantTx and outbox writer. */
  public static final class AdminPool implements AutoCloseable {
    final HikariDataSource dataSource;
    final JdbcClient jdbc;
    final TenantTx tx;
    final OutboxWriter outbox;

    AdminPool(HikariDataSource dataSource, EnvelopeCodec codec, String schema, Clock clock) {
      this.dataSource = dataSource;
      this.jdbc = JdbcClient.create(dataSource);
      this.tx = new TenantTx(new JdbcTransactionManager(dataSource), jdbc);
      this.outbox = new OutboxWriter(jdbc, codec, schema, schema, clock, () -> null);
    }

    @Override
    public void close() {
      dataSource.close();
    }
  }

  @Bean
  SecuritySettings securitySettings(IdentityProperties p) {
    IdentityProperties.Security s = p.security();
    IdentityProperties.Throttle l = s.login();
    ThrottlePolicy login = new ThrottlePolicy(l.free(), l.baseDelay(), l.maxDelay(), l.lockAfter(), l.lockFor(),
        l.window());
    ThrottlePolicy mfa = new ThrottlePolicy(s.mfaFree(), l.baseDelay(), l.maxDelay(), s.mfaLockAfter(), l.lockFor(),
        l.window());
    IdentityProperties.Admin a = p.admin();
    return new SecuritySettings(s.mfaChallengeTtl(), s.mfaMaxAttempts(), s.totpWindow(), s.totpIssuer(), login, mfa,
        ThrottlePolicy.perIp(s.ipFailures(), s.ipWindow()), a.clientId(), a.refreshIdle(), a.refreshAbsolute(),
        a.elevationMax(), a.inviteTtl(), a.inviteLink());
  }

  @Bean(destroyMethod = "close")
  AdminPool identityAdminPool(IdentityProperties p, Environment env, EnvelopeCodec codec, Clock clock) {
    IdentityProperties.Admin a = p.admin();
    HikariConfig c = new HikariConfig();
    c.setPoolName("identity-admin");
    c.setJdbcUrl(a.dbUrl() != null && !a.dbUrl().isBlank() ? a.dbUrl() : env.getRequiredProperty("spring.datasource.url"));
    c.setUsername(a.dbUsername());
    c.setPassword(a.dbPassword());
    c.setMaximumPoolSize(a.dbPoolSize());
    c.setMinimumIdle(0);
    c.setConnectionTimeout(Duration.ofSeconds(5).toMillis());
    c.setInitializationFailTimeout(-1); // start even when the DB is late; health shows it
    return new AdminPool(new HikariDataSource(c), codec, env.getRequiredProperty("dexter.db.schema"), clock);
  }

  // ------------------------------------------------------------------------------------------- infra

  @Bean
  LoginThrottle loginThrottle(StringRedisTemplate redis) {
    return new RedisLoginThrottle(redis);
  }

  @Bean
  MfaChallenges mfaChallenges(StringRedisTemplate redis, JsonMapper json) {
    return new RedisMfaChallenges(redis, json);
  }

  @Bean
  SecretSealer secretSealer(KekRing kek) {
    return new KekSecretSealer(kek);
  }

  @Bean
  MfaStore mfaStore(JdbcClient jdbc) {
    return new MfaRepository(jdbc);
  }

  @Bean
  AdminStore adminStore(JdbcClient jdbc) {
    return new AdminRepository(jdbc);
  }

  @Bean
  AdminDirectory adminDirectory(AdminPool pool, KeysetPager pager) {
    return new AdminDirectoryRepository(pool.jdbc, pager);
  }

  // ------------------------------------------------------------------------------------------- app

  @Bean
  Throttles throttles(LoginThrottle throttle, SecuritySettings s) {
    return new Throttles(throttle, s);
  }

  @Bean
  UserSecondFactor userSecondFactor(MfaStore store, FieldCipher cipher, OutboxWriter outbox, SecuritySettings s,
      Clock clock) {
    return new UserSecondFactor(store, cipher, outbox, s, clock);
  }

  @Bean
  MfaService mfaService(TenantTx tx, MfaStore store, AccountStore accounts, FieldCipher cipher,
      UserSecondFactor factor, OutboxWriter outbox, SecuritySettings s, Clock clock) {
    return new MfaService(tx, store, accounts, cipher, factor, outbox, s, clock);
  }

  @Bean
  AdminAuthService adminAuthService(TenantTx tx, AdminStore store, PasswordHasher hasher,
      PasswordPolicy.BreachedList breached, SecretSealer sealer, MfaChallenges challenges, Throttles throttles,
      AccessTokens tokens, OutboxWriter outbox, SecuritySettings s, Clock clock) {
    return new AdminAuthService(tx, store, hasher, breached, sealer, challenges, throttles, tokens, outbox, s, clock);
  }

  @Bean
  ElevationService elevationService(TenantTx tx, AdminStore store, SecretSealer sealer, Throttles throttles,
      AdminAuthService auth, SecuritySettings s, Clock clock) {
    return new ElevationService(tx, store, sealer, throttles, auth, s, clock);
  }

  @Bean
  AdminDirectoryService adminDirectoryService(AdminPool pool, AdminDirectory directory, Mailer mailer,
      ElevationService elevations, SecuritySettings s, Clock clock) {
    return new AdminDirectoryService(pool.tx, directory, pool.outbox, mailer, elevations, s, clock);
  }
}
