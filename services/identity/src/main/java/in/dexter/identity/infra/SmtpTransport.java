package in.dexter.identity.infra;

import in.dexter.identity.app.Mailer.Mail;
import java.util.function.Consumer;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/** Plain-text mail over SMTP (Mailpit on :1025 in dev). */
public final class SmtpTransport implements Consumer<Mail> {
  private final JavaMailSender sender;
  private final String from;

  public SmtpTransport(JavaMailSender sender, String from) {
    this.sender = sender;
    this.from = from;
  }

  @Override
  public void accept(Mail mail) {
    SimpleMailMessage m = new SimpleMailMessage();
    m.setFrom(from);
    m.setTo(mail.to());
    m.setSubject(mail.subject());
    m.setText(mail.text());
    sender.send(m);
  }
}
