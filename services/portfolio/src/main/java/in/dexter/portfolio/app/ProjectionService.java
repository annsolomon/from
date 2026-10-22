package in.dexter.portfolio.app;

import in.dexter.contracts.events.catalog.CardPublishedV1;
import in.dexter.contracts.events.ledger.ExpenseChangedV1;
import in.dexter.contracts.events.ledger.ExpenseStateV1;
import in.dexter.core.tenant.Tenant;
import in.dexter.db.tenant.TenantTx;
import in.dexter.events.EventEnvelope;
import in.dexter.events.InboxGuard;
import in.dexter.portfolio.app.LinkStore.ExpenseProj;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Event-driven projections (MASTER_PLAN 6.4, P11), each event in one transaction with its inbox row:
 * <ul>
 *   <li>{@code catalog.card.published|retired} → {@code catalog_products_proj} (global; actor PROJECTOR), guarded
 *       by the envelope's aggregateVersion;</li>
 *   <li>{@code ledger.expense.*} → {@code ledger_expenses_proj} (the event's account), guarded by the expense
 *       version; a new or changed card expense is auto-matched, a deleted or changed one loses its open
 *       suggestions.</li>
 * </ul>
 */
public final class ProjectionService {
  public static final String CATALOG_CONSUMER = "catalog-projection";
  public static final String EXPENSE_CONSUMER = "expense-match";
  /** The consumer's actor for global projections (RLS: is_projector()); no token carries it. */
  public static final Tenant PROJECTOR = new Tenant(new UUID(0, 0x9807_1EC7L), "PROJECTOR", null);

  /** What one event did (tests and logs). */
  public enum Outcome { APPLIED, STALE, DUPLICATE, IGNORED }

  private final TenantTx tx;
  private final InboxGuard inbox;
  private final ProjectionStore projections;
  private final LinkStore links;
  private final Matcher matcher;
  private final ObjectMapper mapper;
  private final Clock clock;

  public ProjectionService(TenantTx tx, InboxGuard inbox, ProjectionStore projections, LinkStore links,
      Matcher matcher, ObjectMapper mapper, Clock clock) {
    this.tx = tx;
    this.inbox = inbox;
    this.projections = projections;
    this.links = links;
    this.matcher = matcher;
    this.mapper = mapper;
    this.clock = clock;
  }

  public Outcome onCatalogEvent(EventEnvelope e) {
    if (!e.type().equals(CardPublishedV1.PUBLISHED) && !e.type().equals(CardPublishedV1.RETIRED)) {
      return Outcome.IGNORED;
    }
    CardPublishedV1 p = mapper.treeToValue(e.data(), CardPublishedV1.class);
    Map<String, Object> s = p.snapshot();
    CardStore.Product product = new CardStore.Product(p.cardId(), p.slug(), str(s.get("name")),
        str(s.get("issuerCode")), str(s.get("network")), str(s.get("tier")), num(s.get("annualFeePaise")),
        "retired".equals(p.status()) ? "retired" : "published", p.version(), mapper.writeValueAsString(s));
    return tx.write(PROJECTOR, () -> {
      if (!inbox.firstDelivery(CATALOG_CONSUMER, e.id())) {
        return Outcome.DUPLICATE;
      }
      return projections.upsertProduct(product, e.aggregateVersion()) ? Outcome.APPLIED : Outcome.STALE;
    });
  }

  public Outcome onExpenseEvent(EventEnvelope e) {
    if (!e.type().startsWith("ledger.expense.") || e.accountId() == null) {
      return Outcome.IGNORED;
    }
    ExpenseChangedV1 c = mapper.treeToValue(e.data(), ExpenseChangedV1.class);
    ExpenseStateV1 a = c.after();
    ExpenseStateV1 b = c.before();
    ExpenseStateV1 last = a != null ? a : b;
    if (last == null) {
      return Outcome.IGNORED;
    }
    ExpenseProj proj = new ExpenseProj(c.expenseId(), e.accountId(), last.userCardId(), last.amountPaise(),
        LocalDate.parse(last.day()), last.merchant(), last.merchantId(), last.categoryPath(), a == null, c.version());
    return tx.write(Tenant.user(e.accountId()), () -> {
      if (!inbox.firstDelivery(EXPENSE_CONSUMER, e.id())) {
        return Outcome.DUPLICATE;
      }
      if (!links.upsertExpense(proj)) {
        return Outcome.STALE;
      }
      Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
      boolean matchRelevant = b == null || a == null || b.amountPaise() != a.amountPaise()
          || !Objects.equals(b.userCardId(), a.userCardId()) || !b.day().equals(a.day());
      if (a == null || b != null && matchRelevant) {
        links.rejectOpenFor(c.expenseId(), now);
      }
      if (a != null && matchRelevant) {
        matcher.forExpense(proj, now);
      }
      return Outcome.APPLIED;
    });
  }

  private static String str(Object o) {
    return o == null ? null : o.toString();
  }

  private static long num(Object o) {
    return o instanceof Number n ? n.longValue() : 0;
  }
}
