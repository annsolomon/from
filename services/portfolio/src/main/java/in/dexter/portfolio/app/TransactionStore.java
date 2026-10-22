package in.dexter.portfolio.app;

import in.dexter.db.page.Page;
import in.dexter.db.page.PageRequest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Card transactions, statements and the monthly savings summary (portfolio_app pool, RLS per account). */
public interface TransactionStore {
  record TxnRow(UUID id, UUID accountId, UUID userCardId, UUID statementId, Instant postedAt, long amountPaise,
      String direction, String kind, String description, UUID merchantId, String categoryPath, UUID expenseId,
      String clientRef, String source, Instant createdAt, int version) {}

  record StatementRow(UUID id, UUID accountId, UUID userCardId, LocalDate periodStart, LocalDate periodEnd,
      LocalDate dueDate, long debitsPaise, long creditsPaise, int txnCount, long totalDuePaise, long minDuePaise,
      String status, Instant createdAt, Instant updatedAt, int version) {}

  /** One month of one card (the report's rows). */
  record Savings(UUID userCardId, LocalDate period, long spendPaise, int txnCount, long rewardsValuePaise,
      long feesPaise) {}

  void insert(TxnRow t);

  Optional<TxnRow> find(UUID id);

  Optional<TxnRow> lock(UUID id);

  Optional<UUID> idByClientRef(String clientRef);

  /** A card's transactions, newest first. */
  Page<TxnRow> list(PageRequest page, UUID cardId);

  /** Sets (or clears) the transaction's linked expense. */
  void setExpense(UUID txnId, UUID expenseId);

  /** Unlinked transactions of the card with this amount posted in [from, to). */
  List<TxnRow> matchCandidates(UUID cardId, long amountPaise, Instant from, Instant to);

  // ------------------------------------------------------------------ statements

  /** The statement of the card's cycle starting on {@code periodStart}, locked; empty when not opened yet. */
  Optional<StatementRow> lockStatement(UUID cardId, LocalDate periodStart);

  /** Inserts unless another transaction opened it concurrently; then the caller locks it again. */
  boolean insertStatement(StatementRow s);

  void addToStatement(UUID statementId, long debitPaise, long creditPaise, Instant now);

  /** A card's statements, newest cycle first. */
  Page<StatementRow> statements(PageRequest page, UUID cardId);

  Optional<StatementRow> statement(UUID id);

  /** All tenants: closes ended cycles (owner function); returns how many. */
  int closeEndedStatements(LocalDate today, int limit);

  // ------------------------------------------------------------------ savings

  void addSavings(UUID accountId, UUID cardId, LocalDate period, long spendPaise, int txnCount,
      long rewardsValuePaise, long feesPaise);

  /** Rows of [fromMonth, toMonth] (first days of months). */
  List<Savings> savings(LocalDate fromMonth, LocalDate toMonth);
}
