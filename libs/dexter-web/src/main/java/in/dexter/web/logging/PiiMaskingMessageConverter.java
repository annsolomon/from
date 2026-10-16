package in.dexter.web.logging;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import in.dexter.core.log.PiiMasker;

/**
 * Logback converter that replaces {@code %m}/{@code %msg} with a PII-masked message (MASTER_PLAN 7.4).
 * Registered by {@code in/dexter/web/logging/dexter-logback.xml}.
 */
public class PiiMaskingMessageConverter extends MessageConverter {
  @Override
  public String convert(ILoggingEvent event) {
    return PiiMasker.mask(super.convert(event));
  }
}
