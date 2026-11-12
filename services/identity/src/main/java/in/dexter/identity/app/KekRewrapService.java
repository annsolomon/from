package in.dexter.identity.app;

import in.dexter.db.tenant.TenantTx;
import in.dexter.identity.domain.IdentityTenants;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * KEK re-wrap (P19, ADR-0026, docs/runbooks/key-rotation.md): moves every wrapped key — account DEKs, signing keys,
 * admin TOTP secrets — to the active KEK, so a retired KEK can be removed (or a Transit key's old versions disabled
 * with {@code min_decryption_version}). Reads a batch in one transaction, re-wraps outside it (a Transit call must
 * not hold a database connection), then swaps each row in its own short transaction with compare-and-set, so
 * running on several instances at once is safe. A row that fails to unwrap is logged and skipped, never changed.
 */
public final class KekRewrapService {
  private static final Logger log = LoggerFactory.getLogger(KekRewrapService.class);

  /** One pass. */
  public record Result(int rewrapped, int skipped, int failed) {}

  private final TenantTx tx;
  private final KekRewrapStore store;
  private final KekRewrapper kek;

  public KekRewrapService(TenantTx tx, KekRewrapStore store, KekRewrapper kek) {
    this.tx = tx;
    this.store = store;
    this.kek = kek;
  }

  /** Re-wraps until nothing is left under another KEK (or under {@code from}, when set) or a pass makes no progress. */
  public Result run(@Nullable String from, int batch) {
    int done = 0;
    int skipped = 0;
    int failed = 0;
    String active = kek.activeId();
    while (true) {
      List<KekRewrapStore.Wrapped> rows = tx.read(IdentityTenants.SYSTEM, () -> store.stale(active, from, batch));
      if (rows.isEmpty()) {
        break;
      }
      int progress = 0;
      for (KekRewrapStore.Wrapped row : rows) {
        byte[] sealed;
        try {
          sealed = kek.rewrap(row);
        } catch (RuntimeException e) {
          failed++;
          log.error("KEK_REWRAP_FAILED kind={} id={} kek={} error={}", row.kind(), row.id(), row.kekId(), e.toString());
          continue;
        }
        if (Boolean.TRUE.equals(tx.write(IdentityTenants.SYSTEM, () -> store.swap(row, active, sealed)))) {
          done++;
          progress++;
        } else {
          skipped++;
        }
      }
      if (progress == 0) {
        break; // only failures or rows that moved under us: the next scheduled pass retries
      }
    }
    if (done + failed > 0) {
      log.info("KEK_REWRAP active={} rewrapped={} skipped={} failed={}", active, done, skipped, failed);
    }
    return new Result(done, skipped, failed);
  }

  public List<KekRewrapStore.Usage> usage() {
    return tx.read(IdentityTenants.SYSTEM, store::usage);
  }
}
