package in.dexter.identity.app;

import in.dexter.contracts.events.billing.EntitlementsChangedV1;
import in.dexter.core.tenant.Tenant;
import in.dexter.db.tenant.TenantTx;
import in.dexter.events.EventEnvelope;
import in.dexter.events.InboxGuard;
import java.util.Set;
import tools.jackson.databind.ObjectMapper;

/**
 * The {@code billing-tier} consumer (MASTER_PLAN P16): billing owns what an account has bought; identity keeps the tier
 * so the next access token it issues - a login or a refresh - carries it in the {@code tier} claim. Exactly-once is
 * the inbox row in the same transaction; out-of-order delivery is the version compare in
 * {@link AccountStore#updateTier}. A token already issued keeps its tier until it expires (≤ 10 min), as every other
 * claim does (MASTER_PLAN 7.1).
 */
public final class TierService {
  public static final String CONSUMER = "billing-tier";
  static final Set<String> TIERS = Set.of("FREE", "PREMIUM", "VIP");

  /** What one event did (tests and logs). */
  public enum Outcome { APPLIED, STALE, DUPLICATE, IGNORED }

  private final TenantTx tx;
  private final InboxGuard inbox;
  private final AccountStore accounts;
  private final ObjectMapper mapper;

  public TierService(TenantTx tx, InboxGuard inbox, AccountStore accounts, ObjectMapper mapper) {
    this.tx = tx;
    this.inbox = inbox;
    this.accounts = accounts;
    this.mapper = mapper;
  }

  public Outcome onEntitlementsChanged(EventEnvelope e) {
    if (!EntitlementsChangedV1.TYPE.equals(e.type()) || e.accountId() == null) {
      return Outcome.IGNORED;
    }
    EntitlementsChangedV1 d = mapper.treeToValue(e.data(), EntitlementsChangedV1.class);
    if (d.tier() == null || !TIERS.contains(d.tier()) || d.version() < 1) {
      return Outcome.IGNORED;
    }
    return tx.write(Tenant.user(e.accountId()), () -> {
      if (!inbox.firstDelivery(CONSUMER, e.id())) {
        return Outcome.DUPLICATE;
      }
      return accounts.updateTier(e.accountId(), d.tier(), d.version()) ? Outcome.APPLIED : Outcome.STALE;
    });
  }
}
