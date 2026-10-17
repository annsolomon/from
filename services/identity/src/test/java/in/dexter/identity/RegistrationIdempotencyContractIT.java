package in.dexter.identity;

import in.dexter.identity.app.RegistrationService;
import in.dexter.identity.support.IdentityIT;
import in.dexter.testkit.contract.HttpResult;
import in.dexter.testkit.contract.IdempotencyContract;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Nested;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * MASTER_PLAN 5.5 on POST /v1/auth/register (key required). Registration is pre-auth, so the key scope is the
 * <em>client</em> (5.1): each contract "account" is mapped to one of the public clients (consecutive accounts get
 * different clients, so case 6 "keys are tenant-scoped" compares two clients), and the rows it creates are the
 * accounts whose email carries that contract account's id.
 */
class RegistrationIdempotencyContractIT extends IdentityIT {
  static final List<String> CLIENTS = List.of("dexter-app", "dexter-web", "dexter-trail");
  static final Map<UUID, String> CLIENT_OF = new ConcurrentHashMap<>();
  static final AtomicInteger NEXT = new AtomicInteger();

  @Autowired
  RegistrationService registration;

  @Nested
  class Contract extends IdempotencyContract {
    @Override
    protected HttpResult mutate(UUID account, UUID key, String body) {
      String client = CLIENT_OF.computeIfAbsent(account, a -> CLIENTS.get(NEXT.getAndIncrement() % CLIENTS.size()));
      Map<String, Object> req = Map.of("email", "c-" + account + "-" + body + "@it.test", "password", PASSWORD,
          "clientId", client);
      Res r = key == null ? post("/v1/auth/register", req) : post("/v1/auth/register", req, "Idempotency-Key", key.toString());
      return new HttpResult(r.status(), r.headers(), r.body());
    }

    @Override
    protected String body(int n) {
      return "n" + n;
    }

    @Override
    protected long businessRows(UUID account) {
      return ADMIN.sql("SELECT count(1) FROM identity.accounts WHERE email LIKE :p").param("p", "c-" + account + "-%")
          .query(Long.class).single();
    }

    @Override
    protected long outboxRows(UUID account) {
      return ADMIN.sql("SELECT count(1) FROM identity.outbox o JOIN identity.accounts a ON o.message_key = a.id::text"
              + " WHERE a.email LIKE :p AND o.event_type = 'identity.account.registered'")
          .param("p", "c-" + account + "-%").query(Long.class).single();
    }

    @Override
    protected void failNextMutation() {
      registration.failNextRegistration();
    }
  }
}
