package in.dexter.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import in.dexter.web.logging.PiiMaskingMessageConverter;
import org.junit.jupiter.api.Test;

class PiiLoggingTest {
  @Test
  void converterMasksTheFormattedMessage() {
    LoggerContext ctx = new LoggerContext();
    LoggingEvent e = new LoggingEvent("x", ctx.getLogger("t"), Level.INFO, "login {} from {}", null,
        new Object[] {"asha@demo.in", "9876543210"});
    PiiMaskingMessageConverter c = new PiiMaskingMessageConverter();
    c.setContext(ctx);
    c.start();
    assertThat(c.convert(e)).isEqualTo("login a***@demo.in from [PHONE-10]");
  }
}
