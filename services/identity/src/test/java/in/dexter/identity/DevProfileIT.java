package in.dexter.identity;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.identity.support.IdentityIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.List;
import org.springframework.context.ApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The other half of {@link ProdProfileIT}: the dev controller exists and answers in the dev profile. Deleted on the
 * release branch together with the dev endpoints (docs/release/checklist.md).
 */
@ActiveProfiles("dev")
// An extra context on the shared database: a small pool, closed after the class (the four cached identity contexts
// together must stay under Postgres' connection limit).
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DevProfileIT extends IdentityIT {
  @DynamicPropertySource
  static void smallPool(DynamicPropertyRegistry r) {
    r.add("spring.datasource.hikari.maximum-pool-size", () -> "3");
  }

  @Autowired ApplicationContext ctx;

  /** Every request path this context's controllers map that starts with /_dev. */
  List<String> devPaths() {
    return ctx.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class).getHandlerMethods().keySet()
        .stream().flatMap(info -> info.getPatternValues().stream()).filter(path -> path.startsWith("/_dev")).toList();
  }

  @Test
  void devEndpointsArePresent() {
    assertThat(devPaths()).contains("/_dev/p04/keys");
    var res = get("/_dev/p04/keys");
    assertThat(res.status()).isEqualTo(200);
    assertThat(res.body()).contains("activeKid");
  }
}
