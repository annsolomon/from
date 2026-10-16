package in.dexter.testkit.archfixture.bad.infra;

import in.dexter.cache.CacheInvalidator;
import in.dexter.core.tenant.Tenant;
import in.dexter.db.tenant.TenantTx;
import in.dexter.events.EventEnvelope;
import in.dexter.events.consumer.EventConsumer;

/** ArchRules fixture: an event consumer may own its transaction and bump caches from it (allowed). */
public class GoodConsumer implements EventConsumer.Handler {
  TenantTx tx;
  CacheInvalidator invalidator;

  @Override
  public void handle(EventEnvelope e) {
    tx.writeVoid(Tenant.user(e.accountId()), () -> invalidator.afterCommit(e.accountId(), BadRepository.SCOPE));
  }
}
