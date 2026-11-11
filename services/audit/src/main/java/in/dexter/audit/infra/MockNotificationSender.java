package in.dexter.audit.infra;

import in.dexter.audit.app.NotificationSender;
import in.dexter.audit.app.NotificationStore.Notification;
import in.dexter.audit.domain.Templates;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The dev / test transport for email and push (ADR-0024 §5): it records the delivery in the log and counts it.
 * With {@code failFirst = n} the first n attempts of every notification fail, so the retry path (backoff, then
 * sent) runs on every stack. A real provider replaces it behind the same interface (P19).
 */
public final class MockNotificationSender implements NotificationSender {
  private static final Logger log = LoggerFactory.getLogger(MockNotificationSender.class);
  private final int failFirst;
  private final AtomicLong sent = new AtomicLong();
  private final AtomicLong failed = new AtomicLong();

  public MockNotificationSender(int failFirst) {
    this.failFirst = Math.max(0, failFirst);
  }

  @Override
  public void send(Notification n, Templates.Text text, int attempt) {
    if (attempt <= failFirst) {
      failed.incrementAndGet();
      throw new IllegalStateException("mock " + n.channel() + " transport refused attempt " + attempt);
    }
    sent.incrementAndGet();
    log.info("NOTIFICATION_SENT channel={} template={} notification={} attempt={} title=\"{}\"", n.channel(),
        n.template(), n.id(), attempt, text.title());
  }

  public long sent() {
    return sent.get();
  }

  public long failed() {
    return failed.get();
  }
}
