package in.dexter.portfolio.app;

import in.dexter.contracts.events.portfolio.LinkConfirmedV1;
import in.dexter.core.error.DexterException;
import in.dexter.core.error.NotFoundException;
import in.dexter.core.tenant.Tenant;
import in.dexter.db.page.Page;
import in.dexter.db.page.PageRequest;
import in.dexter.db.tenant.TenantTx;
import in.dexter.events.OutboxWriter;
import in.dexter.portfolio.app.LinkStore.Link;
import in.dexter.portfolio.domain.PortfolioErrors;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Expense ↔ transaction suggestions (MASTER_PLAN P11): the open suggestions (keyset, newest first), confirm (the
 * transaction gets the expense id; rival suggestions of either side are rejected; {@code portfolio.link.confirmed})
 * and reject. Repeating a decision answers the decided link; changing it is refused.
 */
public final class LinkService {
  public record SuggestionView(UUID id, UUID expenseId, UUID transactionId, UUID userCardId, double score,
      Instant postedAt, long amountPaise, String description, LocalDate expenseDay, String expenseMerchant,
      Instant createdAt) {}

  public record LinkView(UUID id, UUID expenseId, UUID transactionId, String status, double score, String method,
      Instant createdAt, Instant decidedAt) {
    static LinkView of(Link l) {
      return new LinkView(l.id(), l.expenseId(), l.cardTxnId(), l.status(), l.score(), l.method(), l.createdAt(),
          l.decidedAt());
    }
  }

  private final TenantTx tx;
  private final LinkStore links;
  private final TransactionStore txns;
  private final OutboxWriter outbox;
  private final Clock clock;

  public LinkService(TenantTx tx, LinkStore links, TransactionStore txns, OutboxWriter outbox, Clock clock) {
    this.tx = tx;
    this.links = links;
    this.txns = txns;
    this.outbox = outbox;
    this.clock = clock;
  }

  public Page<SuggestionView> suggestions(Tenant tenant, PageRequest page) {
    return tx.read(tenant, () -> links.suggestions(page)).map(s -> new SuggestionView(s.link().id(),
        s.link().expenseId(), s.link().cardTxnId(), s.userCardId(), s.link().score(), s.postedAt(), s.amountPaise(),
        s.description(), s.expenseDay(), s.expenseMerchant(), s.link().createdAt()));
  }

  public LinkView confirm(Tenant tenant, UUID id) {
    return tx.write(tenant, () -> {
      Link l = links.lock(id).orElseThrow(() -> new NotFoundException("link"));
      if ("confirmed".equals(l.status())) {
        return LinkView.of(l);
      }
      if (!"suggested".equals(l.status())) {
        throw new DexterException(PortfolioErrors.LINK_DECIDED, "The suggestion was rejected");
      }
      TransactionStore.TxnRow t = txns.lock(l.cardTxnId()).orElseThrow(() -> new NotFoundException("transaction"));
      if (t.expenseId() != null || links.confirmedForTxn(t.id()) || links.confirmedForExpense(l.expenseId())) {
        throw new DexterException(PortfolioErrors.LINK_DECIDED,
            "The transaction or the expense is already linked to another purchase");
      }
      Instant now = now();
      links.decide(id, "confirmed", now);
      txns.setExpense(t.id(), l.expenseId());
      links.rejectRivals(id, l.expenseId(), t.id(), now);
      outbox.append(PortfolioEvents.LINK_CONFIRMED, tenant.accountId(), id, l.version() + 1L, new LinkConfirmedV1(id,
          l.expenseId(), t.id(), t.userCardId(), l.method(), now.toString()));
      return LinkView.of(links.lock(id).orElseThrow());
    });
  }

  public LinkView reject(Tenant tenant, UUID id) {
    return tx.write(tenant, () -> {
      Link l = links.lock(id).orElseThrow(() -> new NotFoundException("link"));
      if ("rejected".equals(l.status())) {
        return LinkView.of(l);
      }
      if (!"suggested".equals(l.status())) {
        throw new DexterException(PortfolioErrors.LINK_DECIDED, "The suggestion was confirmed");
      }
      links.decide(id, "rejected", now());
      return LinkView.of(links.lock(id).orElseThrow());
    });
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }
}
