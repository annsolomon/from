package in.dexter.portfolio.api;

import in.dexter.db.page.PageRequest;
import in.dexter.idempotency.web.IdempotencyKey;
import in.dexter.idempotency.web.IdempotentResponses;
import in.dexter.portfolio.app.ReportService;
import in.dexter.portfolio.app.RewardService;
import in.dexter.security.AuthContext;
import in.dexter.web.page.PageResponse;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Rewards and the portfolio report (MASTER_PLAN P11): {@code GET /v1/rewards/balance}, {@code GET /v1/rewards/ledger}
 * (keyset, optional {@code cardId}), {@code POST /v1/rewards/ledger} (redeem / adjust, Idempotency-Key required),
 * {@code GET /v1/portfolio/report?period=month|year}.
 */
@RestController
public class RewardController {
  private final RewardService rewards;
  private final ReportService reports;

  public RewardController(RewardService rewards, ReportService reports) {
    this.rewards = rewards;
    this.reports = reports;
  }

  @GetMapping("/v1/rewards/balance")
  public RewardService.Balance balance(AuthContext caller) {
    return rewards.balance(Callers.user(caller));
  }

  @GetMapping("/v1/rewards/ledger")
  public PageResponse<RewardService.EntryView> ledger(AuthContext caller, PageRequest page,
      @RequestParam(value = "cardId", required = false) UUID cardId) {
    return PageResponse.from(rewards.ledger(Callers.user(caller), page, cardId));
  }

  @PostMapping("/v1/rewards/ledger")
  public ResponseEntity<String> post(AuthContext caller, @IdempotencyKey(required = true) UUID key,
      @RequestBody RewardService.NewEntry body) {
    return IdempotentResponses.toEntity(rewards.post(Callers.user(caller), key, body));
  }

  @GetMapping("/v1/portfolio/report")
  public ReportService.Report report(AuthContext caller,
      @RequestParam(value = "period", required = false) String period,
      @RequestParam(value = "month", required = false) String month,
      @RequestParam(value = "year", required = false) String year) {
    return reports.report(Callers.user(caller), period, month, year);
  }
}
