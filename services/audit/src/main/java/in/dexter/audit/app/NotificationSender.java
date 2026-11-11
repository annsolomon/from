package in.dexter.audit.app;

import in.dexter.audit.app.NotificationStore.Notification;
import in.dexter.audit.domain.Templates;

/** Delivers one email or push notification; throws when the delivery failed (it is retried with backoff). */
public interface NotificationSender {
  void send(Notification n, Templates.Text text, int attempt);
}
