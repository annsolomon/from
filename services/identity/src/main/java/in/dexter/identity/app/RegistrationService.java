package in.dexter.identity.app;

import in.dexter.contracts.events.identity.AccountRegisteredV1;
import in.dexter.core.error.FieldError;
import in.dexter.core.error.ValidationException;
import in.dexter.core.id.Ids;
import in.dexter.core.tenant.Tenant;
import in.dexter.core.time.Clocks;
import in.dexter.db.tenant.TenantTx;
import in.dexter.events.OutboxWriter;
import in.dexter.identity.app.PreAuthStore.Registration;
import in.dexter.identity.domain.Emails;
import in.dexter.identity.domain.IdentityTenants;
import in.dexter.identity.domain.PasswordPolicy;
import in.dexter.identity.domain.Profiles;
import in.dexter.identity.domain.SecretTokens;
import in.dexter.idempotency.ApiResponse;
import in.dexter.idempotency.IdempotencyEngine;
import in.dexter.idempotency.IdempotentRequest;
import in.dexter.idempotency.IdempotentResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import tools.jackson.databind.ObjectMapper;

/**
 * POST /v1/auth/register (MASTER_PLAN P04): Idempotency-Key required, scoped per client (5.1), always 202.
 *
 * <p>Enumeration safety: a new email, a pending one and an active one all get the same 202 after the same work
 * (one Argon2 hash, one function call, one async email); only the email that arrives differs — a verification
 * link, a fresh link, or "you already have an account".
 */
public final class RegistrationService {
  public static final String PATH = "/v1/auth/register";
  static final Map<String, String> ACCEPTED = Map.of("status", "accepted");

  private final TenantTx tx;
  private final IdempotencyEngine idempotency;
  private final OutboxWriter outbox;
  private final PreAuthStore preAuth;
  private final PasswordHasher hasher;
  private final PasswordPolicy.BreachedList breached;
  private final Mailer mailer;
  private final IdentitySettings settings;
  private final ObjectMapper mapper;
  private final Clock clock;
  /** Test seam (IdempotencyContract case 4): fail after the business write, inside the transaction. */
  private final AtomicBoolean failNext = new AtomicBoolean();

  public RegistrationService(TenantTx tx, IdempotencyEngine idempotency, OutboxWriter outbox, PreAuthStore preAuth,
      PasswordHasher hasher, PasswordPolicy.BreachedList breached, Mailer mailer, IdentitySettings settings,
      ObjectMapper mapper, Clock clock) {
    this.tx = tx;
    this.idempotency = idempotency;
    this.outbox = outbox;
    this.preAuth = preAuth;
    this.hasher = hasher;
    this.breached = breached;
    this.mailer = mailer;
    this.settings = settings;
    this.mapper = mapper;
    this.clock = clock;
  }

  public void failNextRegistration() {
    failNext.set(true);
  }

  public IdempotentResponse register(UUID key, RegisterCommand cmd) {
    String clientId = Clients.require(settings, cmd.clientId());
    String email = Emails.normalize(cmd.email());
    String tz = cmd.tz() == null ? Clocks.DEFAULT_ZONE.getId() : cmd.tz();
    String displayName = cmd.displayName() == null ? null : cmd.displayName().strip();
    List<FieldError> errors = new ArrayList<>();
    if (email == null) {
      errors.add(new FieldError("email", "INVALID_EMAIL", "Enter a valid email address"));
    }
    errors.addAll(PasswordPolicy.check("password", cmd.password(), email, breached));
    errors.addAll(Profiles.nonNull(Profiles.checkTz("tz", tz), Profiles.checkDisplayName("displayName", displayName)));
    if (!errors.isEmpty()) {
      throw new ValidationException(errors);
    }
    String hash = hasher.hash(cmd.password());
    IdempotentRequest fingerprint = IdempotentRequest.of("POST", PATH,
        new RegisterCommand.Fingerprint(email, displayName, tz, clientId), mapper);
    Tenant scope = IdentityTenants.client(clientId);
    return tx.write(scope, () -> idempotency.execute(scope, key, fingerprint, () -> {
      Instant now = clock.instant();
      String token = SecretTokens.newToken();
      Registration r = preAuth.register(Ids.newId(), email, hash, tz, displayName, Ids.newId(),
          SecretTokens.hash(token), now.plus(settings.emailVerifyTtl()), settings.tokensPerHour());
      if (r.created()) {
        outbox.append(IdentityEvents.ACCOUNT_REGISTERED, r.accountId(), r.accountId(), 0,
            new AccountRegisteredV1(r.accountId(), tz, now.toString()));
      }
      if (r.tokenIssued()) {
        mailer.sendAfterCommit(IdentityMails.verify(email, IdentityMails.link(settings.verifyLink(), token),
            settings.emailVerifyTtl().toHours()));
      } else if (!r.created() && "active".equals(r.status())) {
        mailer.sendAfterCommit(IdentityMails.alreadyRegistered(email, settings.signInLink()));
      }
      if (failNext.getAndSet(false)) {
        throw new IllegalStateException("injected failure after the business write");
      }
      return ApiResponse.of(202, ACCEPTED);
    }));
  }
}
