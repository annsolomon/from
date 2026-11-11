package in.dexter.audit.api;

import in.dexter.audit.app.AuditViews.NotificationView;
import in.dexter.audit.app.NotificationService;
import in.dexter.db.page.PageRequest;
import in.dexter.security.AuthContext;
import in.dexter.web.page.PageResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** /v1/notifications (MASTER_PLAN P18): the in-app inbox (keyset, newest first) and {@code /{id}:read}. */
@RestController
@RequestMapping(NotificationService.PATH)
public class NotificationController {
  private final NotificationService notifications;

  public NotificationController(NotificationService notifications) {
    this.notifications = notifications;
  }

  /** The page plus {@code unread}, the count of unread in-app notifications. */
  @GetMapping
  public Map<String, Object> inbox(AuthContext caller, PageRequest page,
      @RequestParam(name = "unread", required = false, defaultValue = "false") boolean unread) {
    NotificationService.Inbox inbox = notifications.inbox(Callers.user(caller), page, unread);
    PageResponse<NotificationView> p = PageResponse.from(inbox.page());
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("data", p.data());
    body.put("page", p.page());
    body.put("unread", inbox.unread());
    return body;
  }

  @PostMapping("/{id}:read")
  public NotificationView read(AuthContext caller, @PathVariable("id") UUID id) {
    return notifications.markRead(Callers.user(caller), id);
  }
}
