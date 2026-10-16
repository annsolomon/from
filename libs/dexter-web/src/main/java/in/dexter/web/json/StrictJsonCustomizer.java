package in.dexter.web.json;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.core.Ordered;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Strict request JSON (MASTER_PLAN 7.3): unknown fields → 400 UNKNOWN_FIELD, duplicate keys → 400
 * DUPLICATE_FIELD, null for a primitive and trailing garbage → 400 MALFORMED_JSON. Ordered after
 * Boot's standard customizer (order 0), which relaxes FAIL_ON_UNKNOWN_PROPERTIES.
 */
public class StrictJsonCustomizer implements JsonMapperBuilderCustomizer, Ordered {
  @Override
  public void customize(JsonMapper.Builder builder) {
    apply(builder);
  }

  /** Also used by tests that build a mapper without Boot. */
  public static JsonMapper.Builder apply(JsonMapper.Builder builder) {
    return builder
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
            DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
            DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
            DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION);
  }

  @Override
  public int getOrder() {
    return 100;
  }
}
