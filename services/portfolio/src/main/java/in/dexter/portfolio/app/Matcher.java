package in.dexter.portfolio.app;

import in.dexter.core.id.Ids;
import in.dexter.portfolio.app.LinkStore.ExpenseProj;
import in.dexter.portfolio.app.TransactionStore.TxnRow;
import in.dexter.portfolio.domain.BillingCycle;
import in.dexter.portfolio.domain.MatchScore;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * The auto-matcher (MASTER_PLAN P11): an expense and a card transaction on the same card, for the same amount,
 * within ±2 days, whose merchants agree ({@link MatchScore}) become a suggestion; the user confirms or rejects it.
 * Runs inside the caller's transaction (a recorded transaction, or a consumed ledger expense event), from either
 * side; the best-scoring candidate wins (ties: closer date, then the older row). A pair is suggested once.
 */
public final class Matcher {
  private final TransactionStore txns;
  private final LinkStore links;

  public Matcher(TransactionStore txns, LinkStore links) {
    this.txns = txns;
    this.links = links;
  }

  /** A new debit: find the expense it pays for. Returns the suggestion's score, or -1. */
  public double forTransaction(TxnRow t, Instant now) {
    if (!"debit".equals(t.direction()) || t.expenseId() != null || links.confirmedForTxn(t.id())) {
      return -1;
    }
    LocalDate day = BillingCycle.day(t.postedAt());
    ExpenseProj best = null;
    double bestScore = -1;
    long bestDays = Long.MAX_VALUE;
    for (ExpenseProj e : links.matchCandidates(t.userCardId(), t.amountPaise(),
        day.minusDays(MatchScore.MAX_DAYS), day.plusDays(MatchScore.MAX_DAYS))) {
      long days = ChronoUnit.DAYS.between(e.day(), day);
      double s = MatchScore.score(MatchScore.merchant(e.merchant(), e.merchantId(), t.description(), t.merchantId()),
          days);
      if (s > bestScore || s == bestScore && s >= 0 && Math.abs(days) < bestDays) {
        best = e;
        bestScore = s;
        bestDays = Math.abs(days);
      }
    }
    return best == null || bestScore < 0 ? -1 : suggest(t.accountId(), best.expenseId(), t.id(), bestScore, now);
  }

  /** A card expense from ledger: find the transaction that paid it. Returns the suggestion's score, or -1. */
  public double forExpense(ExpenseProj e, Instant now) {
    if (e.deleted() || e.userCardId() == null || links.confirmedForExpense(e.expenseId())) {
      return -1;
    }
    Instant from = e.day().minusDays(MatchScore.MAX_DAYS).atStartOfDay(BillingCycle.ZONE).toInstant();
    Instant to = e.day().plusDays(MatchScore.MAX_DAYS + 1L).atStartOfDay(BillingCycle.ZONE).toInstant();
    TxnRow best = null;
    double bestScore = -1;
    long bestDays = Long.MAX_VALUE;
    for (TxnRow t : txns.matchCandidates(e.userCardId(), e.amountPaise(), from, to)) {
      if (!"debit".equals(t.direction())) {
        continue;
      }
      long days = ChronoUnit.DAYS.between(e.day(), BillingCycle.day(t.postedAt()));
      double s = MatchScore.score(MatchScore.merchant(e.merchant(), e.merchantId(), t.description(), t.merchantId()),
          days);
      if (s > bestScore || s == bestScore && s >= 0 && Math.abs(days) < bestDays) {
        best = t;
        bestScore = s;
        bestDays = Math.abs(days);
      }
    }
    return best == null || bestScore < 0 ? -1 : suggest(e.accountId(), e.expenseId(), best.id(), bestScore, now);
  }

  private double suggest(UUID account, UUID expenseId, UUID txnId, double score, Instant now) {
    boolean added = links.suggest(new LinkStore.Link(Ids.newId(), account, expenseId, txnId, "suggested", score, "auto",
        now, null, 0));
    return added ? score : -1;
  }
}
