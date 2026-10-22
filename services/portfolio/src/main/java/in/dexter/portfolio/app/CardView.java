package in.dexter.portfolio.app;

import in.dexter.portfolio.domain.BillingCycle;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** API representation of a user card with its catalog product and current cycle. The version is the ETag. */
public record CardView(UUID id, UUID cardProductId, String productSlug, String productName, String issuerCode,
    String nickname, String last4, String network, int statementDay, int dueOffsetDays, Long creditLimitPaise,
    LocalDate openedOn, String status, Cycle currentCycle, Instant createdAt, Instant updatedAt, int version) {

  public record Cycle(LocalDate periodStart, LocalDate periodEnd, LocalDate dueDate) {}

  static CardView of(CardStore.CardRow c, CardStore.Product p, LocalDate today) {
    BillingCycle b = BillingCycle.containing(today, c.statementDay(), c.dueOffsetDays());
    return new CardView(c.id(), c.cardProductId(), p == null ? null : p.slug(), p == null ? null : p.name(),
        p == null ? null : p.issuerCode(), c.nickname(), c.last4(), c.network(), c.statementDay(), c.dueOffsetDays(),
        c.creditLimitPaise(), c.openedOn(), c.status(), new Cycle(b.periodStart(), b.periodEnd(), b.dueDate()),
        c.createdAt(), c.updatedAt(), c.version());
  }
}
