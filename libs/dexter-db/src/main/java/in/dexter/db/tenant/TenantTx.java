package in.dexter.db.tenant;

import in.dexter.core.tenant.Tenant;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs work inside a transaction with the RLS context set (MASTER_PLAN 3.3).
 *
 * <p>{@code set_config(..., true)} is transaction-local: Postgres drops the context at commit or
 * rollback, so a pooled connection can never carry one tenant's context to the next borrower
 * (proven by {@code TenantTxIT} with pool size 1). {@link #read} runs a read-only transaction,
 * which routes to the replica when the DataSource comes from {@link in.dexter.db.DexterDataSources}.
 *
 * <p>Nesting: a nested call for the <em>same</em> tenant joins the outer transaction. A nested call
 * for a different tenant, or a write nested in a read, is rejected.
 */
public final class TenantTx {
  static final String SET_CONTEXT =
      "SELECT set_config('app.account_id', :a, true), set_config('app.actor_role', :r, true)";

  private final TransactionTemplate rw;
  private final TransactionTemplate ro;
  private final JdbcClient jdbc;

  public TenantTx(PlatformTransactionManager txManager, JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.rw = new TransactionTemplate(txManager);
    this.rw.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    this.ro = new TransactionTemplate(txManager);
    this.ro.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    this.ro.setReadOnly(true);
  }

  public <T> T write(Tenant tenant, Supplier<T> work) {
    return run(rw, tenant, work, false);
  }

  public <T> T read(Tenant tenant, Supplier<T> work) {
    return run(ro, tenant, work, true);
  }

  public void writeVoid(Tenant tenant, Runnable work) {
    write(tenant, () -> {
      work.run();
      return null;
    });
  }

  private <T> T run(TransactionTemplate tpl, Tenant tenant, Supplier<T> work, boolean readOnly) {
    Objects.requireNonNull(tenant, "tenant");
    Objects.requireNonNull(work, "work");
    if (TenantScope.isBound()) {
      Tenant outer = TenantScope.CURRENT.get();
      if (!outer.equals(tenant)) {
        throw new IllegalStateException("Nested TenantTx for a different tenant is not allowed");
      }
      if (!readOnly && TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
        throw new IllegalStateException("TenantTx.write nested inside TenantTx.read");
      }
      return work.get();
    }
    return tpl.execute(status -> {
      jdbc.sql(SET_CONTEXT)
          .param("a", tenant.accountId().toString())
          .param("r", tenant.role())
          .query()
          .singleRow();
      return ScopedValue.where(TenantScope.CURRENT, tenant).call(work::get);
    });
  }
}
