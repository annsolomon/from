package in.dexter.reco.support;

import in.dexter.contracts.events.catalog.CardPublishedV1;
import in.dexter.contracts.events.ledger.ExpenseChangedV1;
import in.dexter.contracts.events.ledger.ExpenseStateV1;
import in.dexter.contracts.events.portfolio.CardChangedV1;
import in.dexter.events.EventEnvelope;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Envelopes as ledger, portfolio and catalog publish them (MASTER_PLAN 6.1), for calling reco's projection
 * handlers directly. The catalog snapshot is built with the same keys {@code CardSnapshots} writes, so a test
 * card is shaped exactly like a real published one.
 */
public final class Events {
  private Events() {}

  // ---------------------------------------------------------------- ledger

  public static ExpenseStateV1 state(long paise, String day, String categoryPath) {
    return new ExpenseStateV1(paise, day, categoryPath, "upi", null, null, "Test merchant", day + "T06:00:00Z");
  }

  public static EventEnvelope expense(String type, UUID account, UUID expenseId, long version,
      ExpenseStateV1 before, ExpenseStateV1 after) {
    return new EventEnvelope(UUID.randomUUID(), type, 1, "ledger", Instant.now(), account, expenseId, version, null,
        RecoIT.JSON.valueToTree(new ExpenseChangedV1(expenseId, version, before, after, Instant.now().toString())));
  }

  public static EventEnvelope created(UUID account, UUID expenseId, ExpenseStateV1 after) {
    return expense(ExpenseChangedV1.CREATED, account, expenseId, 0, null, after);
  }

  public static EventEnvelope updated(UUID account, UUID expenseId, long version, ExpenseStateV1 before,
      ExpenseStateV1 after) {
    return expense(ExpenseChangedV1.UPDATED, account, expenseId, version, before, after);
  }

  public static EventEnvelope deleted(UUID account, UUID expenseId, long version, ExpenseStateV1 before) {
    return expense(ExpenseChangedV1.DELETED, account, expenseId, version, before, null);
  }

  // ---------------------------------------------------------------- portfolio

  public static EventEnvelope cardAdded(UUID account, UUID userCardId, UUID productId, long version) {
    return card(CardChangedV1.ADDED, account, userCardId, productId, "active", version);
  }

  public static EventEnvelope cardRemoved(UUID account, UUID userCardId, UUID productId, long version) {
    return card(CardChangedV1.REMOVED, account, userCardId, productId, "removed", version);
  }

  public static EventEnvelope card(String type, UUID account, UUID userCardId, UUID productId, String status,
      long version) {
    return new EventEnvelope(UUID.randomUUID(), type, 1, "portfolio", Instant.now(), account, userCardId, version,
        null, RecoIT.JSON.valueToTree(new CardChangedV1(userCardId, productId, "Test card", "4242", "VISA", 5,
            status, (int) version, Instant.now().toString())));
  }

  // ---------------------------------------------------------------- catalog

  /** One reward rule in the snapshot's own shape. */
  public static Map<String, Object> rule(UUID ruleId, String categoryPath, String rewardType, int rateBps,
      Long pointValueMilliPaise, Long capPaise, String capPeriod, List<String> excludedPaths, int priority) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", ruleId.toString());
    m.put("categoryPath", categoryPath);
    m.put("merchantId", null);
    m.put("merchantSlug", null);
    m.put("rewardType", rewardType);
    m.put("rateBps", rateBps);
    m.put("pointValueMilliPaise", pointValueMilliPaise);
    m.put("capPaise", capPaise);
    m.put("capPeriod", capPeriod);
    m.put("minTxnPaise", 0);
    m.put("excludedPaths", excludedPaths);
    m.put("priority", priority);
    return m;
  }

  public static Map<String, Object> milestone(long spendThresholdPaise, String period, long benefitValuePaise) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("spendThresholdPaise", spendThresholdPaise);
    m.put("period", period);
    m.put("benefitType", "voucher");
    m.put("benefitValuePaise", benefitValuePaise);
    return m;
  }

  /** The published snapshot of a card, with the keys CardSnapshots writes. */
  public static Map<String, Object> snapshot(UUID cardId, String slug, String name, long annualFeePaise,
      Long feeWaiverSpendPaise, List<Map<String, Object>> rules, Map<String, Object> milestone) {
    Map<String, Object> s = new LinkedHashMap<>();
    s.put("id", cardId.toString());
    s.put("slug", slug);
    s.put("name", name);
    s.put("issuerCode", "HDFC");
    s.put("issuerName", "HDFC Bank");
    s.put("network", "VISA");
    s.put("tier", "premium");
    s.put("joiningFeePaise", 0);
    s.put("annualFeePaise", annualFeePaise);
    s.put("feeWaiverSpendPaise", feeWaiverSpendPaise);
    s.put("aprBps", 4200);
    s.put("forexMarkupBps", 350);
    s.put("fuelSurchargeWaiverBps", 100);
    s.put("features", new ArrayList<>());
    s.put("rules", rules);
    s.put("milestone", milestone);
    return s;
  }

  public static EventEnvelope cardPublished(UUID cardId, String slug, int catalogVersion, long aggregateVersion,
      Map<String, Object> snapshot) {
    return catalog(CardPublishedV1.PUBLISHED, cardId, slug, "published", catalogVersion, aggregateVersion,
        snapshot);
  }

  public static EventEnvelope cardRetired(UUID cardId, String slug, int catalogVersion, long aggregateVersion,
      Map<String, Object> snapshot) {
    return catalog(CardPublishedV1.RETIRED, cardId, slug, "retired", catalogVersion, aggregateVersion, snapshot);
  }

  public static EventEnvelope catalog(String type, UUID cardId, String slug, String status, int catalogVersion,
      long aggregateVersion, Map<String, Object> snapshot) {
    // catalog's topic is keyed by card id and carries no account: a card is global
    return new EventEnvelope(UUID.randomUUID(), type, 1, "catalog", Instant.now(), null, cardId, aggregateVersion,
        null, RecoIT.JSON.valueToTree(new CardPublishedV1(cardId, slug, status, catalogVersion, "2026-01-01",
            "retired".equals(status) ? "2026-12-31" : null, snapshot)));
  }
}
