package in.dexter.reco.app;

import in.dexter.contracts.events.catalog.CardPublishedV1;
import in.dexter.contracts.events.ledger.ExpenseChangedV1;
import in.dexter.contracts.events.ledger.ExpenseStateV1;
import in.dexter.contracts.events.portfolio.CardChangedV1;
import in.dexter.core.tenant.Tenant;
import in.dexter.db.tenant.TenantTx;
import in.dexter.events.EventEnvelope;
import in.dexter.events.InboxGuard;
import in.dexter.reco.domain.Windows;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The three projections reco scores from (MASTER_PLAN P14), each event applied in one transaction with its inbox
 * row:
 *
 * <ul>
 *   <li>{@code ledger.expense.*} → {@code spend_month} as a delta, then the touched root's three windows are
 *       rebuilt from it, and the account is marked dirty;</li>
 *   <li>{@code portfolio.card.added|updated|removed} → {@code held_cards_proj}, guarded by the card's version;</li>
 *   <li>{@code catalog.card.published|retired} → {@code catalog_cards_proj} + {@code catalog_rules_proj} (global,
 *       actor PROJECTOR), guarded by the envelope's aggregate version.</li>
 * </ul>
 *
 * <p><b>Why the inbox and not a version compare for spend.</b> A delta is not idempotent under replay, and a
 * {@code spend_month} row aggregates many expenses whose versions advance independently — so a per-row version
 * compare would wrongly skip a version-2 event for one expense after a version-9 event for another. The inbox row,
 * written in the same transaction, is the exactly-once guard; {@code projected_version} records the last version
 * applied for diagnosis only. Same reasoning as planning's budget spend (ADR-0019 §1), and it is why
 * {@code SpendPropertyIT} compares the projection against a recomputed sum rather than trusting the path.
 *
 * <p>A catalog change does <em>not</em> fan out a dirty flag to every account: the read path notices it through the
 * state hash instead (A-83), so one republished card does not enqueue a recompute for the whole user base.
 */
public final class ProjectionService {
  public static final String EXPENSE_CONSUMER = "spend-profile";
  public static final String CARD_CONSUMER = "held-cards";
  public static final String CATALOG_CONSUMER = "catalog-rules";

  /** The consumer's actor for the global catalog projection (RLS: is_projector()); no token carries it. */
  public static final Tenant PROJECTOR = new Tenant(new UUID(0, 0x9807_1EC7L), "PROJECTOR", null);

  /** What one event did. */
  public enum Outcome { APPLIED, STALE, DUPLICATE, IGNORED }

  private final TenantTx tx;
  private final InboxGuard inbox;
  private final RecoStore store;
  private final ObjectMapper mapper;
  private final Clock clock;

  public ProjectionService(TenantTx tx, InboxGuard inbox, RecoStore store, ObjectMapper mapper, Clock clock) {
    this.tx = tx;
    this.inbox = inbox;
    this.store = store;
    this.mapper = mapper;
    this.clock = clock;
  }

  // ---------------------------------------------------------------- ledger -> spend profile

  public Outcome onExpenseEvent(EventEnvelope e) {
    if (!e.type().startsWith("ledger.expense.") || e.accountId() == null) {
      return Outcome.IGNORED;
    }
    ExpenseChangedV1 c = mapper.treeToValue(e.data(), ExpenseChangedV1.class);
    if (c.before() == null && c.after() == null) {
      return Outcome.IGNORED;
    }
    UUID account = e.accountId();
    return tx.write(Tenant.user(account), () -> {
      if (!inbox.firstDelivery(EXPENSE_CONSUMER, e.id())) {
        return Outcome.DUPLICATE;
      }
      // -before, +after: an edited amount, day or category settles both sides in one transaction
      List<String> touched = new ArrayList<>(2);
      apply(account, c.before(), -1, touched);
      apply(account, c.after(), 1, touched);
      Instant now = now();
      LocalDate today = LocalDate.ofInstant(now, RecommendationService.ZONE);
      for (String root : touched) {
        rebuild(account, root, today, c.version());
      }
      store.markDirty(account, "ledger", c.version(), now);
      return Outcome.APPLIED;
    });
  }

  private void apply(UUID account, ExpenseStateV1 state, int sign, List<String> touched) {
    if (state == null) {
      return;
    }
    String root = root(state.categoryPath());
    LocalDate month = Windows.month(LocalDate.parse(state.day()));
    store.addSpend(account, root, month, sign * state.amountPaise(), sign);
    if (!touched.contains(root)) {
      touched.add(root);
    }
  }

  /** Recomputes one root's three windows from {@code spend_month}; drops the rows when nothing is left. */
  private void rebuild(UUID account, String root, LocalDate today, long version) {
    LocalDate from = Windows.start("12m", today);
    LocalDate to = Windows.month(today);
    List<RecoStore.MonthRow> months = store.monthsOf(account, root, from, to);
    List<RecoStore.ProfileRow> rows = new ArrayList<>(Windows.ALL.size());
    boolean any = false;
    for (String window : Windows.ALL) {
      long total = 0;
      int txns = 0;
      int monthsWithSpend = 0;
      for (RecoStore.MonthRow m : months) {
        if (!Windows.contains(window, today, m.month()) || m.totalPaise() <= 0) {
          continue;
        }
        total += m.totalPaise();
        txns += m.txnCount();
        monthsWithSpend++;
      }
      if (total > 0) {
        any = true;
      }
      rows.add(new RecoStore.ProfileRow(root, window, Windows.monthlyAverage(total, monthsWithSpend), total, txns));
    }
    if (any) {
      store.putProfile(account, root, rows, version);
    } else {
      store.deleteProfile(account, root);
    }
  }

  // ---------------------------------------------------------------- portfolio -> held cards

  public Outcome onCardEvent(EventEnvelope e) {
    if (!e.type().startsWith("portfolio.card.") || e.accountId() == null) {
      return Outcome.IGNORED;
    }
    CardChangedV1 c = mapper.treeToValue(e.data(), CardChangedV1.class);
    UUID account = e.accountId();
    return tx.write(Tenant.user(account), () -> {
      if (!inbox.firstDelivery(CARD_CONSUMER, e.id())) {
        return Outcome.DUPLICATE;
      }
      boolean applied = "removed".equals(c.status())
          ? store.deleteHeldCard(account, c.userCardId(), e.aggregateVersion())
          : store.upsertHeldCard(account,
              new RecoStore.HeldCard(c.userCardId(), c.cardProductId(), e.aggregateVersion()),
              e.aggregateVersion());
      if (!applied) {
        return Outcome.STALE;
      }
      store.markDirty(account, "portfolio", e.aggregateVersion(), now());
      return Outcome.APPLIED;
    });
  }

  // ---------------------------------------------------------------- catalog -> card terms and rules

  public Outcome onCatalogEvent(EventEnvelope e) {
    if (!e.type().equals(CardPublishedV1.PUBLISHED) && !e.type().equals(CardPublishedV1.RETIRED)) {
      return Outcome.IGNORED;
    }
    CardPublishedV1 p = mapper.treeToValue(e.data(), CardPublishedV1.class);
    Map<String, Object> s = p.snapshot();
    JsonNode snap = mapper.valueToTree(s);
    RecoStore.CardRow card = new RecoStore.CardRow(p.cardId(), p.slug(), text(snap, "name"),
        text(snap, "issuerCode"), text(snap, "network"), text(snap, "tier"), num(snap, "annualFeePaise"),
        nullableNum(snap, "feeWaiverSpendPaise"), milestoneJson(snap), "retired".equals(p.status()) ? "retired"
            : "published", p.version(), e.aggregateVersion());
    List<RecoStore.RuleRow> rules = rules(p.cardId(), snap);
    return tx.write(PROJECTOR, () -> {
      if (!inbox.firstDelivery(CATALOG_CONSUMER, e.id())) {
        return Outcome.DUPLICATE;
      }
      return store.upsertCard(card, rules, e.aggregateVersion()) ? Outcome.APPLIED : Outcome.STALE;
    });
  }

  private List<RecoStore.RuleRow> rules(UUID cardId, JsonNode snap) {
    List<RecoStore.RuleRow> out = new ArrayList<>();
    JsonNode rs = snap.get("rules");
    if (rs == null || !rs.isArray()) {
      return out;
    }
    for (JsonNode r : rs) {
      UUID ruleId = uuid(r, "id");
      if (ruleId == null) {
        continue;   // a rule without an id cannot be keyed; the snapshot always has one
      }
      List<String> excluded = new ArrayList<>();
      JsonNode ex = r.get("excludedPaths");
      if (ex != null && ex.isArray()) {
        ex.forEach(x -> excluded.add(x.asString()));
      }
      String rewardType = text(r, "rewardType");
      out.add(new RecoStore.RuleRow(cardId, ruleId, text(r, "categoryPath"), uuid(r, "merchantId"),
          rewardType == null ? "cashback" : rewardType, (int) num(r, "rateBps"), num(r, "pointValueMilliPaise"),
          nullableNum(r, "capPaise"), text(r, "capPeriod"), num(r, "minTxnPaise"), excluded,
          (int) num(r, "priority")));
    }
    return out;
  }

  private String milestoneJson(JsonNode snap) {
    JsonNode m = snap.get("milestone");
    return m == null || m.isNull() ? null : m.toString();
  }

  static String root(String categoryPath) {
    if (categoryPath == null || categoryPath.isBlank()) {
      return "general";
    }
    int dot = categoryPath.indexOf('.');
    return dot < 0 ? categoryPath : categoryPath.substring(0, dot);
  }

  private static String text(JsonNode n, String f) {
    JsonNode v = n == null ? null : n.get(f);
    return v == null || v.isNull() ? null : v.asString();
  }

  private static long num(JsonNode n, String f) {
    JsonNode v = n == null ? null : n.get(f);
    return v == null || v.isNull() || !v.isNumber() ? 0 : v.asLong();
  }

  private static Long nullableNum(JsonNode n, String f) {
    JsonNode v = n == null ? null : n.get(f);
    return v == null || v.isNull() || !v.isNumber() ? null : v.asLong();
  }

  private static UUID uuid(JsonNode n, String f) {
    String s = text(n, f);
    try {
      return s == null ? null : UUID.fromString(s);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }
}
