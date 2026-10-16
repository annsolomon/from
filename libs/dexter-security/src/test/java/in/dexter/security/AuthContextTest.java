package in.dexter.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.dexter.core.error.ForbiddenException;
import in.dexter.core.tenant.Tenant;
import in.dexter.security.AuthContext.Kind;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuthContextTest {
  static final UUID ACCOUNT = UUID.fromString("0192f0c8-7e2a-7b3c-8d4e-5f6a7b8c9d0e");
  static final ZoneId TZ = ZoneId.of("Asia/Kolkata");

  static AuthContext ctx(Kind kind, UUID account, String service, Set<String> roles) {
    Instant now = Instant.parse("2026-09-27T10:00:00Z");
    return new AuthContext(kind, account != null ? account.toString() : "svc:" + service, account, service,
        account != null ? "sid-1" : null, "dexter-app", Set.of("ledger"), Set.of(), roles, Set.of(), Set.of("pwd"),
        now, false, TZ, "FREE", "jti-1", now, now.plusSeconds(600));
  }

  @Test
  void userTenantIsRoleUserInTheAccountZone() {
    Tenant t = ctx(Kind.USER, ACCOUNT, null, Set.of("USER")).tenant();
    assertThat(t.accountId()).isEqualTo(ACCOUNT);
    assertThat(t.role()).isEqualTo(Tenant.USER);
    assertThat(t.tz()).isEqualTo(TZ);
  }

  @Test
  void adminRoleIsChosenExplicitly() {
    AuthContext admin = ctx(Kind.ADMIN, ACCOUNT, null, Set.of("SUPPORT"));
    assertThat(admin.tenant().role()).isEqualTo(Tenant.USER);
    assertThat(admin.tenantAs(Tenant.SUPPORT).role()).isEqualTo(Tenant.SUPPORT);
    assertThatThrownBy(() -> admin.tenantAs(Tenant.SUPER_ADMIN)).isInstanceOf(ForbiddenException.class);
  }

  @Test
  void serviceTokensHaveNoTenant() {
    AuthContext svc = ctx(Kind.SERVICE, null, "reco", Set.of());
    assertThat(svc.isService()).isTrue();
    assertThatThrownBy(svc::tenant).isInstanceOf(ForbiddenException.class);
  }

  @Test
  void kindMustMatchIdentity() {
    assertThatThrownBy(() -> ctx(Kind.SERVICE, ACCOUNT, null, Set.of())).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ctx(Kind.USER, null, "reco", Set.of())).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void toStringHasNoTokenMaterial() {
    assertThat(ctx(Kind.USER, ACCOUNT, null, Set.of()).toString()).contains(ACCOUNT.toString()).doesNotContain("sid-1");
  }
}
