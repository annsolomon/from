package in.dexter.identity.infra;

import in.dexter.identity.app.Mailer.Mail;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Keeps sent mail in memory ({@code dexter.identity.mail.mode=memory}: tests). */
public final class MemoryTransport implements Consumer<Mail> {
  private final List<Mail> sent = new CopyOnWriteArrayList<>();

  @Override
  public void accept(Mail mail) {
    sent.add(mail);
  }

  public List<Mail> sent() {
    return List.copyOf(sent);
  }

  public void clear() {
    sent.clear();
  }
}
