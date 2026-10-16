package in.dexter.db;

import javax.sql.DataSource;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;

/**
 * Primary/replica routing (MASTER_PLAN 3.3). Uses Spring's {@link LazyConnectionDataSourceProxy}
 * read-only routing (Spring 6.1.2+): a transaction marked read-only (TenantTx.read) obtains its
 * connection from the replica, everything else from the primary. The replica pool must be
 * configured read-only by default (Hikari {@code readOnly=true}).
 */
public final class DexterDataSources {
  private DexterDataSources() {}

  /** Returns the DataSource that the transaction manager and JdbcClient must both use. */
  public static DataSource readWriteRouting(DataSource primary, DataSource replica) {
    LazyConnectionDataSourceProxy proxy = new LazyConnectionDataSourceProxy(primary);
    if (replica != null) {
      proxy.setReadOnlyDataSource(replica);
    }
    return proxy;
  }
}
