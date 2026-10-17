package in.dexter.ledger.app;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** {@code ledger.categorization_rules} of the current tenant (needs a TenantTx). */
public interface RuleStore {
  /** {@code matchType}: {@code contains} (pattern = normalised text) or {@code merchant_id} (pattern = uuid). */
  record Rule(UUID id, String matchType, String pattern, String categoryPath, int priority, Instant createdAt) {}

  /** In evaluation order: priority ascending, then id. */
  List<Rule> list();

  int count();

  void insert(UUID id, UUID accountId, String matchType, String pattern, String categoryPath, int priority);

  boolean delete(UUID id);
}
