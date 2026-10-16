package in.dexter.testkit.archfixture.bad.infra;

import in.dexter.cache.CacheInvalidator;
import in.dexter.cache.CacheScope;
import in.dexter.core.tenant.Tenant;
import in.dexter.db.tenant.TenantTx;
import in.dexter.security.web.RequiresPermission;
import java.time.Duration;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/** ArchRules fixture: an infra class that starts transactions, bumps caches and carries a security annotation. */
public class BadRepository {
  static final CacheScope SCOPE = CacheScope.tenant("x", Duration.ofMinutes(1));

  @RequiresPermission("admin:all")
  public void guarded() {}

  public void startsATransaction(TenantTx tx) {
    tx.writeVoid(Tenant.user(UUID.randomUUID()), () -> {});
  }

  public void bumps(CacheInvalidator invalidator) {
    invalidator.afterCommit(UUID.randomUUID(), SCOPE);
  }

  @Transactional
  public void annotated() {}
}
