package in.dexter.identity.app;

/**
 * Transactional security email (verification, reset, "already registered"). {@link #sendAfterCommit} sends only
 * if the surrounding transaction commits, asynchronously, so response timing does not depend on the mail server.
 */
public interface Mailer {
  record Mail(String to, String subject, String text) {}

  void sendAfterCommit(Mail mail);
}
