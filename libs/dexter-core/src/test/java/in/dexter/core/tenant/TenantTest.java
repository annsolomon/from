package in.dexter.core.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class TenantTest {
  @Test
  void defaultsAndValidation() {
    Tenant t = Tenant.user(UUID.randomUUID());
    assertThat(t.role()).isEqualTo("USER");
    assertThat(t.tz().getId()).isEqualTo("Asia/Kolkata");
    assertThatThrownBy(() -> new Tenant(UUID.randomUUID(), "user'; drop", null)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Tenant(null, "USER", null)).isInstanceOf(NullPointerException.class);
  }
}
