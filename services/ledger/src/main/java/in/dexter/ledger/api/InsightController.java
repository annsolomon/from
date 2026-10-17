package in.dexter.ledger.api;

import in.dexter.core.error.FieldError;
import in.dexter.core.error.ValidationException;
import in.dexter.ledger.app.InsightService;
import in.dexter.security.AuthContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /v1/calendar?month=} and {@code GET /v1/summary?from&to&groupBy=} (P08; IST dates). */
@RestController
public class InsightController {
  private final InsightService insights;

  public InsightController(InsightService insights) {
    this.insights = insights;
  }

  @GetMapping("/v1/calendar")
  public InsightService.Calendar calendar(AuthContext caller, @RequestParam("month") String month) {
    return insights.calendar(Callers.user(caller), month);
  }

  @GetMapping("/v1/summary")
  public InsightService.Summary summary(AuthContext caller, @RequestParam("from") String from,
      @RequestParam("to") String to, @RequestParam(value = "groupBy", required = false) String groupBy) {
    List<FieldError> errors = new ArrayList<>();
    LocalDate f = ExpenseController.date("from", from, errors);
    LocalDate t = ExpenseController.date("to", to, errors);
    if (!errors.isEmpty()) {
      throw new ValidationException(errors);
    }
    return insights.summary(Callers.user(caller), f, t, groupBy);
  }
}
