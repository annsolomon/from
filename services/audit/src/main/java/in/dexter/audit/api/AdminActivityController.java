package in.dexter.audit.api;

import in.dexter.audit.app.ActivityService;
import in.dexter.audit.app.AuditViews.DashboardView;
import in.dexter.security.AuthContext;
import in.dexter.security.web.RequiresPermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** /v1/admin/activity/dashboard (MASTER_PLAN P18): requests, errors and latency per hour and per service. */
@RestController
@RequestMapping(ActivityService.PATH)
public class AdminActivityController {
  private final ActivityService activity;

  public AdminActivityController(ActivityService activity) {
    this.activity = activity;
  }

  @GetMapping("/dashboard")
  @RequiresPermission("audit:read")
  public DashboardView dashboard(AuthContext caller,
      @RequestParam(name = "hours", required = false, defaultValue = "24") int hours) {
    return activity.dashboard(Callers.operator(caller), hours);
  }
}
