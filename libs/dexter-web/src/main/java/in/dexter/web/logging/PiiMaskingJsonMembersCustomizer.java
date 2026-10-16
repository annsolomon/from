package in.dexter.web.logging;

import in.dexter.core.log.PiiMasker;
import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer;

/**
 * Masks every string value of structured (ECS JSON) log events (MASTER_PLAN 7.4). Enabled with
 * {@code logging.structured.json.customizer=in.dexter.web.logging.PiiMaskingJsonMembersCustomizer}.
 */
public class PiiMaskingJsonMembersCustomizer implements StructuredLoggingJsonMembersCustomizer<Object> {
  @Override
  public void customize(JsonWriter.Members<Object> members) {
    members.applyingValueProcessor(JsonWriter.ValueProcessor.of(String.class, v -> v == null ? null : PiiMasker.mask(v)));
  }
}
