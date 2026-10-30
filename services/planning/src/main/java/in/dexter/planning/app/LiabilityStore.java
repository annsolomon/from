package in.dexter.planning.app;

import in.dexter.db.page.Page;
import in.dexter.db.page.PageRequest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Liabilities and their stored amortization schedules (planning_app pool, RLS per account). */
public interface LiabilityStore {

  /**
   * @param totalInterestPaise the schedule's own sum, not {@code emiPaise * tenureMonths - principalPaise}: the
   *     last instalment is the balancing one, so the product overstates it by the rounding adjustment
   */
  record LiabilityRow(UUID id, UUID accountId, String kind, String lender, long principalPaise, int aprBps,
      int tenureMonths, LocalDate startDate, long emiPaise, long totalInterestPaise, long totalPayablePaise,
      String status, Instant createdAt, Instant updatedAt, int version) {}

  record InstallmentRow(UUID liabilityId, int number, LocalDate dueOn, long principalPaise, long interestPaise,
      long totalPaise, long outstandingAfterPaise) {}

  /** The liability and its whole schedule in one transaction; the schedule is never rewritten. */
  void insert(LiabilityRow l, List<InstallmentRow> schedule);

  Optional<LiabilityRow> find(UUID id);

  Optional<LiabilityRow> lock(UUID id);

  void update(LiabilityRow l);

  Page<LiabilityRow> list(PageRequest page);

  List<LiabilityRow> active();

  Page<InstallmentRow> schedule(PageRequest page, UUID liabilityId);
}
