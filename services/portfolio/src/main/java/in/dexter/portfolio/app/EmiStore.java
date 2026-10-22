package in.dexter.portfolio.app;

import in.dexter.db.page.Page;
import in.dexter.db.page.PageRequest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** EMI plans and their schedules (portfolio_app pool, RLS per account). */
public interface EmiStore {
  record Plan(UUID id, UUID accountId, UUID userCardId, UUID cardTxnId, String description, long principalPaise,
      int annualRateBps, int tenureMonths, long processingFeePaise, int gstBps, LocalDate firstDueDate, long emiPaise,
      long totalInterestPaise, long totalGstPaise, String status, Instant createdAt, Instant updatedAt, int version) {}

  record Installment(UUID planId, int number, LocalDate dueDate, long principalPaise, long interestPaise,
      long gstPaise, long totalPaise, long outstandingAfterPaise, String status) {}

  void insert(Plan p, List<Installment> schedule);

  Optional<Plan> find(UUID id);

  Optional<Plan> lock(UUID id);

  void update(Plan p);

  /** Marks the plan's unpaid instalments cancelled (closed / foreclosed plans). */
  void cancelRemaining(UUID planId);

  Page<Plan> list(PageRequest page);

  Page<Installment> schedule(PageRequest page, UUID planId);
}
