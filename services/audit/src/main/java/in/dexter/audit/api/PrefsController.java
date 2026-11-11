package in.dexter.audit.api;

import in.dexter.audit.app.AuditViews.PrefsView;
import in.dexter.audit.app.NotificationService;
import in.dexter.security.AuthContext;
import in.dexter.web.etag.ETags;
import in.dexter.web.etag.IfMatchVersion;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * /v1/notification-prefs (MASTER_PLAN P18): channel switches and muted categories. PATCH needs If-Match
 * ({@code "0"} before the first save); security cannot be muted.
 */
@RestController
@RequestMapping(NotificationService.PREFS_PATH)
public class PrefsController {
  private final NotificationService notifications;

  public PrefsController(NotificationService notifications) {
    this.notifications = notifications;
  }

  @GetMapping
  public ResponseEntity<PrefsView> get(AuthContext caller) {
    PrefsView v = notifications.prefs(Callers.user(caller));
    return ResponseEntity.ok().eTag(ETags.of(v.version())).body(v);
  }

  @PatchMapping
  public ResponseEntity<PrefsView> patch(AuthContext caller, @IfMatchVersion long version,
      @RequestBody NotificationService.PrefsPatch body) {
    PrefsView v = notifications.patchPrefs(Callers.user(caller), version, body);
    return ResponseEntity.ok().eTag(ETags.of(v.version())).body(v);
  }
}
