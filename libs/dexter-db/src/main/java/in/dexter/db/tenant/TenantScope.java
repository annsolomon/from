package in.dexter.db.tenant;

import in.dexter.core.tenant.Tenant;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The tenant of the current {@link TenantTx} unit of work (MASTER_PLAN 3.3), held in a
 * {@link ScopedValue} so it can never leak to another request on a pooled or virtual thread.
 * Repositories call {@link #require()} first: it fails fast when code runs outside TenantTx.
 */
public final class TenantScope {
  static final ScopedValue<Tenant> CURRENT = ScopedValue.newInstance();

  private TenantScope() {}

  public static boolean isBound() {
    return CURRENT.isBound();
  }

  /**
   * The current tenant. Throws {@link IllegalStateException} (a programming error, never a client
   * error) when there is no TenantTx or no active transaction.
   */
  public static Tenant require() {
    if (!CURRENT.isBound()) {
      throw new IllegalStateException("No tenant bound: repository called outside TenantTx");
    }
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("Tenant bound but no active transaction");
    }
    return CURRENT.get();
  }
}
