package in.dexter.identity.app;

import in.dexter.core.error.NotFoundException;
import in.dexter.db.page.Page;
import in.dexter.db.page.PageRequest;
import in.dexter.db.tenant.TenantTx;
import in.dexter.identity.app.SessionStore.SessionView;
import in.dexter.identity.domain.RevokeReason;
import in.dexter.security.AuthContext;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

/** GET /v1/me/sessions (keyset) and DELETE /v1/me/sessions/{id}. */
public final class SessionService {
  private final TenantTx tx;
  private final SessionStore sessions;
  private final AuthService auth;
  private final Clock clock;

  public SessionService(TenantTx tx, SessionStore sessions, AuthService auth, Clock clock) {
    this.tx = tx;
    this.sessions = sessions;
    this.auth = auth;
    this.clock = clock;
  }

  public Page<SessionView> list(AuthContext caller, PageRequest page) {
    return tx.read(caller.tenant(), () -> sessions.listActive(page));
  }

  /** 404 for an unknown (or foreign — RLS hides it) session; an already revoked one is a no-op. */
  public void revoke(AuthContext caller, UUID sessionId) {
    tx.writeVoid(caller.tenant(), () -> {
      if (sessions.find(caller.accountId(), sessionId).isEmpty()) {
        throw new NotFoundException("session");
      }
      auth.revokeSessions(caller.accountId(), List.of(sessionId), RevokeReason.USER_REVOKED, clock.instant());
    });
  }
}
