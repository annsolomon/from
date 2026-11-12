package in.dexter.identity;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.identity.api.DevKeysController;
import in.dexter.identity.support.IdentityIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * P19 step 7 (release guard): in the prod profile the trail's dev controller is not a bean and its path does not
 * answer. {@link DevProfileIT} shows the same path answering 200 in the dev profile, so this is not vacuous.
 */
@ActiveProfiles("prod")
// An extra context on the shared database: a small pool, closed after the class (the four cached identity contexts
// together must stay under Postgres' connection limit).
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ProdProfileIT extends IdentityIT {
  @DynamicPropertySource
  static void smallPool(DynamicPropertyRegistry r) {
    r.add("spring.datasource.hikari.maximum-pool-size", () -> "3");
  }

  @Autowired ApplicationContext ctx;

  @Test
  void devEndpointsAreAbsent() {
    assertThat(ctx.getEnvironment().getActiveProfiles()).containsExactly("prod");
    assertThat(ctx.getBeanNamesForType(DevKeysController.class)).isEmpty();
    assertThat(get("/_dev/p04/keys").status()).isIn(401, 403, 404);
  }
}
