package in.dexter.portfolio.app;

import in.dexter.db.page.Page;
import in.dexter.db.page.PageRequest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Expense ↔ card transaction links and the ledger expense projection (portfolio_app pool, RLS per account). */
public interface LinkStore {
  record ExpenseProj(UUID expenseId, UUID accountId, UUID userCardId, long amountPaise, LocalDate day, String merchant,
      UUID merchantId, String categoryPath, boolean deleted, long projectedVersion) {}

  record Link(UUID id, UUID accountId, UUID expenseId, UUID cardTxnId, String status, double score, String method,
      Instant createdAt, Instant decidedAt, int version) {}

  /** A suggestion with both sides, for the review list. */
  record Suggestion(Link link, UUID userCardId, Instant postedAt, long amountPaise, String description,
      LocalDate expenseDay, String expenseMerchant) {}

  /** Writes the projection when {@code version} is newer than the stored one (ProjectionVersionGuard). */
  boolean upsertExpense(ExpenseProj e);

  Optional<ExpenseProj> expense(UUID expenseId);

  /** Live card expenses of the card with this amount on days [from, to], not yet confirmed-linked. */
  List<ExpenseProj> matchCandidates(UUID cardId, long amountPaise, LocalDate from, LocalDate to);

  /** A new suggestion unless the pair exists (any status); true when inserted. */
  boolean suggest(Link l);

  Page<Suggestion> suggestions(PageRequest page);

  Optional<Link> lock(UUID id);

  void decide(UUID id, String status, Instant now);

  boolean confirmedForTxn(UUID txnId);

  boolean confirmedForExpense(UUID expenseId);

  /** Rejects the other open suggestions of the expense or the transaction (one purchase, one link). */
  int rejectRivals(UUID linkId, UUID expenseId, UUID txnId, Instant now);

  /** Rejects the open suggestions of an expense that disappeared or changed (ledger delete/update). */
  int rejectOpenFor(UUID expenseId, Instant now);
}
