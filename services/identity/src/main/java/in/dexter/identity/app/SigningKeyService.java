package in.dexter.identity.app;

import in.dexter.db.tenant.TenantTx;
import in.dexter.identity.app.SigningKeyStore.KeyRow;
import in.dexter.identity.domain.IdentityTenants;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JWT signing keys (MASTER_PLAN 7.1): {@code next → active → retired}.
 *
 * <ul>
 *   <li>There is always an {@code active} key (signs) and a {@code next} key (already published in the JWKS, so
 *       every service knows it before it signs anything).</li>
 *   <li>Every {@code keyRotateEvery} (30 days) the active key retires, next becomes active and a new next is made.
 *       One instance rotates at a time (advisory lock).</li>
 *   <li>Retired keys stay published for {@code keyRetainRetired}, longer than any token they signed can live.</li>
 * </ul>
 * Each instance keeps a snapshot of the table and reloads it every minute (and after its own rotation), so a
 * rotation by another instance is picked up quickly; tokens signed meanwhile with the old key remain valid.
 */
public final class SigningKeyService {
  private static final Logger log = LoggerFactory.getLogger(SigningKeyService.class);

  /** The published keys and the one that signs. */
  public record Snapshot(KeyRow active, List<KeyRow> published, String jwks, Instant loadedAt) {}

  private final TenantTx tx;
  private final SigningKeyStore store;
  private final KeyCrypto crypto;
  private final IdentitySettings settings;
  private final Clock clock;
  private volatile Snapshot snapshot;

  public SigningKeyService(TenantTx tx, SigningKeyStore store, KeyCrypto crypto, IdentitySettings settings, Clock clock) {
    this.tx = tx;
    this.store = store;
    this.crypto = crypto;
    this.settings = settings;
    this.clock = clock;
  }

  /** Startup and scheduled: makes sure active + next exist, rotates when due, drops expired retired keys. */
  public Snapshot rotateIfDue() {
    return maintain(false);
  }

  /** Rotates now regardless of age (ops runbook / dev trail / tests). */
  public Snapshot rotateNow() {
    return maintain(true);
  }

  private Snapshot maintain(boolean force) {
    tx.writeVoid(IdentityTenants.SYSTEM, () -> {
      if (!store.tryLock()) {
        return;
      }
      Instant now = clock.instant();
      List<KeyRow> rows = store.all();
      KeyRow active = find(rows, "active");
      KeyRow next = find(rows, "next");
      if (active == null) {
        KeyRow promote = next != null ? next : insertNext();
        store.activate(promote.kid(), now);
        log.info("SIGNING_KEY_ACTIVATED kid={} reason=bootstrap", promote.kid());
        next = null;
      } else if (force || !active.activatedAt().plus(settings.keyRotateEvery()).isAfter(now)) {
        if (next == null) {
          next = insertNext();
        }
        store.retire(active.kid(), now);
        store.activate(next.kid(), now);
        log.info("SIGNING_KEY_ROTATED retired={} active={}", active.kid(), next.kid());
        next = null;
      }
      if (next == null) {
        insertNext();
      }
      int dropped = store.deleteRetiredBefore(now.minus(settings.keyRetainRetired()));
      if (dropped > 0) {
        log.info("SIGNING_KEY_DROPPED count={}", dropped);
      }
    });
    // Another instance may hold the lock while bootstrapping the very first key: wait for its commit.
    for (int attempt = 1; ; attempt++) {
      try {
        return reload();
      } catch (IllegalStateException e) {
        if (attempt >= 50) {
          throw e;
        }
        try {
          Thread.sleep(100);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          throw e;
        }
      }
    }
  }

  private KeyRow insertNext() {
    KeyCrypto.NewKey k = crypto.generate();
    store.insertNext(k.kid(), k.publicJwk(), k.privateEnc(), k.kekId());
    return new KeyRow(k.kid(), k.publicJwk(), k.privateEnc(), k.kekId(), "next", clock.instant(), null, null);
  }

  /** Reloads the snapshot from the table. */
  public Snapshot reload() {
    List<KeyRow> rows = tx.read(IdentityTenants.SYSTEM, store::all);
    KeyRow active = find(rows, "active");
    if (active == null) {
      throw new IllegalStateException("no active signing key (rotateIfDue has not run)");
    }
    List<KeyRow> published = rows.stream()
        .sorted(Comparator.comparing(KeyRow::createdAt).reversed().thenComparing(KeyRow::kid))
        .toList();
    Snapshot s = new Snapshot(active, published, crypto.jwks(published), clock.instant());
    snapshot = s;
    return s;
  }

  public Snapshot current() {
    Snapshot s = snapshot;
    if (s == null || Duration.between(s.loadedAt(), clock.instant()).compareTo(Duration.ofMinutes(1)) > 0) {
      s = reload();
    }
    return s;
  }

  /** The public JWKS JSON ({@code /.well-known/jwks.json}). */
  public String jwks() {
    return current().jwks();
  }

  public String sign(TokenClaimsSpec claims) {
    return crypto.sign(current().active(), claims);
  }

  private static KeyRow find(List<KeyRow> rows, String status) {
    return rows.stream().filter(r -> Objects.equals(r.status(), status)).findFirst().orElse(null);
  }
}
