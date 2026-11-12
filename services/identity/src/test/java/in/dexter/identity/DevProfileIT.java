package in.dexter.identity;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.identity.api.DevKeysController;
import in.dexter.identity.support.IdentityIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

/** The other half of {@link ProdProfileIT}: the dev controller exists and answers in the dev profile. */
@ActiveProfiles("dev")
class DevProfileIT extends IdentityIT {
  @Autowired ApplicationContext ctx;

  @Test
  void devEndpointsArePresent() {
    assertThat(ctx.getBeanNamesForType(DevKeysController.class)).hasSize(1);
    var res = get("/_dev/p04/keys");
    assertThat(res.status()).isEqualTo(200);
    assertThat(res.body()).contains("activeKid");
  }
}
