package in.dexter.identity.infra;

import in.dexter.identity.app.Mailer;
import in.dexter.identity.domain.Emails;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Sends after the surrounding transaction commits (nothing is sent for a rolled-back or replayed request), on a
 * virtual thread, at most {@code maxInFlight} at a time (excess mail is dropped with an error log — every flow can
 * be retried by the user: register again, forgot password again). Never logs the body (it carries a token).
 * A durable mail queue is P18 (notifications).
 */
public final class AfterCommitMailer implements Mailer, AutoCloseable {
  private static final Logger log = LoggerFactory.getLogger(AfterCommitMailer.class);

  private final Consumer<Mail> transport;
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
  private final Semaphore inFlight;

  public AfterCommitMailer(Consumer<Mail> transport, int maxInFlight) {
    this.transport = transport;
    this.inFlight = new Semaphore(Math.max(1, maxInFlight));
  }

  @Override
  public void sendAfterCommit(Mail mail) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
          submit(mail);
        }
      });
    } else {
      submit(mail);
    }
  }

  private void submit(Mail mail) {
    if (!inFlight.tryAcquire()) {
      log.error("MAIL_DROPPED to={} subject={} reason=too-many-in-flight", Emails.mask(mail.to()), mail.subject());
      return;
    }
    try {
      executor.execute(() -> {
        try {
          transport.accept(mail);
          log.info("MAIL_SENT to={} subject={}", Emails.mask(mail.to()), mail.subject());
        } catch (RuntimeException e) {
          log.error("MAIL_FAILED to={} subject={} error={}", Emails.mask(mail.to()), mail.subject(), e.toString());
        } finally {
          inFlight.release();
        }
      });
    } catch (RejectedExecutionException e) {
      inFlight.release();
      log.error("MAIL_DROPPED to={} reason=shutting-down", Emails.mask(mail.to()));
    }
  }

  @Override
  public void close() {
    executor.shutdown();
  }
}
