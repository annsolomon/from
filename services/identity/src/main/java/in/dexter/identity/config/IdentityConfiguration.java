package in.dexter.identity.config;

import com.nimbusds.jose.jwk.JWKSet;
import in.dexter.core.time.Clocks;
import in.dexter.db.page.KeysetPager;
import in.dexter.db.tenant.TenantTx;
import in.dexter.events.OutboxWriter;
import in.dexter.identity.app.AccessTokens;
import in.dexter.identity.app.AccountStore;
import in.dexter.identity.app.AuthService;
import in.dexter.identity.app.EmailVerificationService;
import in.dexter.identity.app.FieldCipher;
import in.dexter.identity.app.IdentitySettings;
import in.dexter.identity.app.KeyCrypto;
import in.dexter.identity.app.Mailer;
import in.dexter.identity.app.MfaChallenges;
import in.dexter.identity.app.MfaStore;
import in.dexter.identity.app.PasswordHasher;
import in.dexter.identity.app.PasswordService;
import in.dexter.identity.app.PreAuthStore;
import in.dexter.identity.app.ProfileService;
import in.dexter.identity.app.RefreshTokenStore;
import in.dexter.identity.app.RegistrationService;
import in.dexter.identity.app.SecuritySettings;
import in.dexter.identity.app.ServiceTokenService;
import in.dexter.identity.app.SessionService;
import in.dexter.identity.app.SessionStore;
import in.dexter.identity.app.SigningKeyService;
import in.dexter.identity.app.SigningKeyStore;
import in.dexter.identity.app.Throttles;
import in.dexter.identity.app.UserSecondFactor;
import in.dexter.identity.app.VerificationTokenStore;
import in.dexter.identity.domain.PasswordPolicy;
import in.dexter.identity.domain.SecretTokens;
import in.dexter.identity.infra.AccountRepository;
import in.dexter.identity.infra.AfterCommitMailer;
import in.dexter.identity.infra.Argon2Hasher;
import in.dexter.identity.infra.BundledBreachedList;
import in.dexter.identity.infra.EnvelopeCipher;
import in.dexter.identity.infra.JoseKeyCrypto;
import in.dexter.identity.infra.KekRing;
import in.dexter.identity.infra.MemoryTransport;
import in.dexter.identity.infra.PreAuthRepository;
import in.dexter.identity.infra.RefreshTokenRepository;
import in.dexter.identity.infra.SessionRepository;
import in.dexter.identity.infra.SigningKeyRepository;
import in.dexter.identity.infra.SmtpTransport;
import in.dexter.identity.infra.VerificationTokenRepository;
import in.dexter.idempotency.IdempotencyEngine;
import in.dexter.security.jwt.JwksCache;
import in.dexter.security.jwt.JwtSettings;
import in.dexter.security.jwt.JwtVerifier;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wires identity (MASTER_PLAN 2.5: config wires, app/infra stay framework-light). The libraries provide TenantTx,
 * KeysetPager, IdempotencyEngine, OutboxWriter and the security interceptor; identity provides its own
 * {@link JwtVerifier} that reads the public keys straight from its signing-key table (no HTTP to itself).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IdentityProperties.class)
public class IdentityConfiguration {
  private static final Logger log = LoggerFactory.getLogger(IdentityConfiguration.class);
  /** This service's audience in user tokens. */
  public static final String AUDIENCE = "identity";

  @Bean
  Clock identityClock() {
    return Clocks.systemUtc();
  }

  @Bean
  IdentitySettings identitySettings(IdentityProperties p) {
    require(p.issuer(), "dexter.identity.issuer");
    Map<String, IdentitySettings.ServiceClient> clients = new LinkedHashMap<>();
    p.serviceClients().forEach((name, c) -> {
      if (c.secret() == null || c.secret().isBlank()) {
        log.warn("SERVICE_CLIENT_DISABLED client={} reason=no-secret", name);
        return;
      }
      clients.put(name, new IdentitySettings.ServiceClient(name,
          SecretTokens.sha256(c.secret().getBytes(StandardCharsets.UTF_8)),
          Set.copyOf(c.audiences() == null ? List.of() : c.audiences())));
    });
    return new IdentitySettings(p.issuer(), p.audiences(), p.accessTokenTtl(), p.refreshIdle(), p.refreshAbsolute(),
        new HashSet<>(p.publicClients()), p.defaultClient(), p.emailVerifyTtl(), p.passwordResetTtl(),
        p.tokensPerHour(), p.links().verifyEmail(), p.links().resetPassword(), p.links().signIn(),
        p.keys().rotateEvery(), p.keys().retainRetired(), p.serviceTokenTtl(), clients);
  }

  // ------------------------------------------------------------------------------------------- infra

  @Bean
  KekRing kekRing(IdentityProperties p) {
    if (p.kek().keys().isEmpty()) {
      throw new IllegalStateException("dexter.identity.kek.keys is empty: set IDENTITY_KEK_K1 (base64 of 32 random"
          + " bytes; dev value in .env.example)");
    }
    return KekRing.fromBase64(p.kek().activeId(), p.kek().keys());
  }

  @Bean
  FieldCipher fieldCipher(JdbcClient jdbc, KekRing kek, IdentityProperties p) {
    require(p.phonePepper(), "dexter.identity.phone-pepper (IDENTITY_PHONE_PEPPER)");
    return new EnvelopeCipher(jdbc, kek, Base64.getDecoder().decode(p.phonePepper().strip()));
  }

  @Bean
  KeyCrypto keyCrypto(KekRing kek) {
    return new JoseKeyCrypto(kek);
  }

  @Bean
  PasswordHasher passwordHasher(IdentityProperties p) {
    return new Argon2Hasher(p.hashing().maxConcurrent(), p.hashing().maxWait());
  }

  @Bean
  PasswordPolicy.BreachedList breachedPasswords() {
    BundledBreachedList list = new BundledBreachedList();
    log.info("BREACHED_LIST_LOADED entries={}", list.size());
    return list;
  }

  @Bean
  MemoryTransport memoryMailTransport() {
    return new MemoryTransport();
  }

  @Bean(destroyMethod = "close")
  AfterCommitMailer mailer(IdentityProperties p, ObjectProvider<JavaMailSender> smtp, MemoryTransport memory) {
    Consumer<Mailer.Mail> transport = switch (p.mail().mode()) {
      case "memory" -> memory;
      case "smtp" -> new SmtpTransport(smtp.getIfAvailable(() -> {
        throw new IllegalStateException("dexter.identity.mail.mode=smtp needs spring.mail.host");
      }), p.mail().from());
      default -> throw new IllegalStateException("dexter.identity.mail.mode must be smtp or memory");
    };
    return new AfterCommitMailer(transport, p.mail().maxInFlight());
  }

  @Bean
  PreAuthStore preAuthStore(JdbcClient jdbc) {
    return new PreAuthRepository(jdbc);
  }

  @Bean
  AccountStore accountStore(JdbcClient jdbc) {
    return new AccountRepository(jdbc);
  }

  @Bean
  SessionStore sessionStore(JdbcClient jdbc, KeysetPager pager) {
    return new SessionRepository(jdbc, pager);
  }

  @Bean
  RefreshTokenStore refreshTokenStore(JdbcClient jdbc) {
    return new RefreshTokenRepository(jdbc);
  }

  @Bean
  VerificationTokenStore verificationTokenStore(JdbcClient jdbc) {
    return new VerificationTokenRepository(jdbc);
  }

  @Bean
  SigningKeyStore signingKeyStore(JdbcClient jdbc) {
    return new SigningKeyRepository(jdbc);
  }

  // ------------------------------------------------------------------------------------------- app

  @Bean
  SigningKeyService signingKeyService(TenantTx tx, SigningKeyStore store, KeyCrypto crypto, IdentitySettings s,
      Clock clock) {
    return new SigningKeyService(tx, store, crypto, s, clock);
  }

  @Bean
  AccessTokens accessTokens(SigningKeyService keys, IdentitySettings s, Clock clock) {
    return new AccessTokens(keys, s, clock);
  }

  @Bean
  RegistrationService registrationService(TenantTx tx, IdempotencyEngine idempotency, OutboxWriter outbox,
      PreAuthStore preAuth, PasswordHasher hasher, PasswordPolicy.BreachedList breached, Mailer mailer,
      IdentitySettings s, JsonMapper mapper, Clock clock) {
    return new RegistrationService(tx, idempotency, outbox, preAuth, hasher, breached, mailer, s, mapper, clock);
  }

  @Bean
  EmailVerificationService emailVerificationService(TenantTx tx, PreAuthStore preAuth, OutboxWriter outbox,
      Clock clock) {
    return new EmailVerificationService(tx, preAuth, outbox, clock);
  }

  @Bean
  AuthService authService(TenantTx tx, PreAuthStore preAuth, AccountStore accounts, SessionStore sessions,
      RefreshTokenStore refreshTokens, PasswordHasher hasher, AccessTokens tokens, OutboxWriter outbox,
      IdentitySettings s, SecuritySettings security, Throttles throttles, MfaChallenges challenges, MfaStore mfa,
      UserSecondFactor secondFactor, Clock clock) {
    return new AuthService(tx, preAuth, accounts, sessions, refreshTokens, hasher, tokens, outbox, s, security,
        throttles, challenges, mfa, secondFactor, clock);
  }

  @Bean
  PasswordService passwordService(TenantTx tx, PreAuthStore preAuth, AccountStore accounts, SessionStore sessions,
      RefreshTokenStore refreshTokens, VerificationTokenStore tokens, PasswordHasher hasher,
      PasswordPolicy.BreachedList breached, Mailer mailer, AuthService auth, IdentitySettings s, Clock clock) {
    return new PasswordService(tx, preAuth, accounts, sessions, refreshTokens, tokens, hasher, breached, mailer, auth,
        s, clock);
  }

  @Bean
  ProfileService profileService(TenantTx tx, AccountStore accounts, FieldCipher cipher) {
    return new ProfileService(tx, accounts, cipher);
  }

  @Bean
  SessionService sessionService(TenantTx tx, SessionStore sessions, AuthService auth, Clock clock) {
    return new SessionService(tx, sessions, auth, clock);
  }

  @Bean
  ServiceTokenService serviceTokenService(AccessTokens tokens, IdentitySettings s) {
    return new ServiceTokenService(tokens, s);
  }

  // ------------------------------------------------------------------------------------------- web + security

  /**
   * Verifies tokens for identity's own endpoints with the same verifier every service uses, fed directly from the
   * signing-key snapshot. The security web auto-configuration picks this bean up (default-deny interceptor).
   */
  @Bean
  JwtVerifier identityJwtVerifier(SigningKeyService keys, IdentitySettings s, Clock clock) {
    JwksCache cache = new JwksCache(() -> {
      try {
        return JWKSet.parse(keys.jwks());
      } catch (java.text.ParseException e) {
        throw new java.io.IOException("own JWKS unreadable", e);
      }
    }, clock, Duration.ofSeconds(30), Duration.ofSeconds(1), Duration.ofHours(1));
    return new JwtVerifier(JwtSettings.of(s.issuer(), AUDIENCE), cache, clock);
  }

  @Bean
  WebMvcConfigurer identityWebMvcConfigurer() {
    return new WebMvcConfigurer() {
      @Override
      public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new ClientCredentialsArgumentResolver());
      }
    };
  }

  /** Signing keys exist (and are current) before the first request. */
  @Bean
  ApplicationRunner signingKeysBootstrap(SigningKeyService keys) {
    return args -> {
      SigningKeyService.Snapshot s = keys.rotateIfDue();
      log.info("SIGNING_KEYS_READY active={} published={}", s.active().kid(), s.published().size());
    };
  }

  private static void require(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(name + " must be set");
    }
  }
}
