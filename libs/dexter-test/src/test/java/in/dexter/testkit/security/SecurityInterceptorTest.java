package in.dexter.testkit.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import in.dexter.core.tenant.Tenant;
import in.dexter.core.time.MutableClock;
import in.dexter.security.AuthContext;
import in.dexter.security.jwt.JwtVerifier;
import in.dexter.security.web.AuthContextArgumentResolver;
import in.dexter.security.web.InternalEndpoint;
import in.dexter.security.web.PublicEndpoint;
import in.dexter.security.web.RequiresPermission;
import in.dexter.security.web.RequiresStepUp;
import in.dexter.security.web.SecurityInterceptor;
import in.dexter.testkit.contract.HttpResult;
import in.dexter.testkit.support.Mvc;
import in.dexter.web.error.ProblemDetailsAdvice;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Default deny, caller kinds, permissions and step-up over MockMvc with the real ProblemDetail advice. */
class SecurityInterceptorTest {
  static final UUID ACCOUNT = UUID.fromString("0192f0c8-7e2a-7b3c-8d4e-5f6a7b8c9d0e");
  static final Instant T0 = Instant.parse("2026-09-27T10:00:00Z");

  @RestController
  static class Secured {
    @GetMapping("/v1/me")
    public String me(AuthContext ctx, Tenant tenant) {
      return ctx.kind() + ":" + tenant.accountId() + ":" + tenant.role();
    }

    @PublicEndpoint
    @GetMapping("/v1/public")
    public String pub() {
      return "open";
    }

    @GetMapping("/_dev/ping")
    public String dev() {
      return "dev";
    }

    @RequiresPermission("expense.read")
    @GetMapping("/_dev/admin")
    public String devAdmin() {
      return "guarded";
    }

    @RequiresPermission({"card.write", "card.publish"})
    @GetMapping("/v1/admin/cards")
    public String admin() {
      return "admin";
    }

    @RequiresStepUp
    @GetMapping("/v1/me/password")
    public String sensitive() {
      return "changed";
    }

    @InternalEndpoint(callers = "reco")
    @GetMapping("/internal/profile")
    public String internal(AuthContext ctx) {
      return "svc:" + ctx.service();
    }
  }

  TestJwt idp;
  MutableClock clock;
  MockMvc mvc;

  @BeforeEach
  void setUp() {
    idp = TestJwt.create();
    clock = new MutableClock(T0);
    JwtVerifier verifier = idp.verifier("ledger", clock);
    SecurityInterceptor interceptor = new SecurityInterceptor(verifier, clock, Duration.ofMinutes(10),
        List.of("/_dev/**", "/error"));
    mvc = MockMvcBuilders.standaloneSetup(new Secured())
        .setControllerAdvice(new ProblemDetailsAdvice())
        .addInterceptors(interceptor)
        .setCustomArgumentResolvers(new AuthContextArgumentResolver())
        .build();
  }

  HttpResult call(String path, String token) throws Exception {
    MockHttpServletRequestBuilder req = get(path);
    if (token != null) {
      req.header("Authorization", "Bearer " + token);
    }
    return Mvc.call(mvc, req);
  }

  TestJwt.Token user() {
    return idp.user(ACCOUNT).audience("ledger").issuedAt(clock.instant());
  }

  @Test
  void noTokenIs401WithChallenge() throws Exception {
    HttpResult r = call("/v1/me", null);
    assertThat(r.status()).isEqualTo(401);
    assertThat(r.body()).contains("\"code\":\"UNAUTHENTICATED\"");
    assertThat(r.headers().get("WWW-Authenticate")).isEqualTo("Bearer realm=\"dexter\"");
  }

  @Test
  void badTokenIs401InvalidTokenWithoutDetails() throws Exception {
    HttpResult r = call("/v1/me", idp.user(ACCOUNT).audience("billing").issuedAt(T0).sign());
    assertThat(r.status()).isEqualTo(401);
    assertThat(r.headers().get("WWW-Authenticate")).contains("error=\"invalid_token\"");
    assertThat(r.body()).doesNotContain("AUDIENCE").doesNotContain("billing");
  }

  @Test
  void identityHeadersAreIgnored() throws Exception {
    HttpResult r = Mvc.call(mvc, get("/v1/me").header("X-Account-Id", ACCOUNT.toString()).header("X-Actor-Role", "SUPER_ADMIN"));
    assertThat(r.status()).isEqualTo(401);
  }

  @Test
  void userGetsAuthContextAndUserTenant() throws Exception {
    HttpResult r = call("/v1/me", user().sign());
    assertThat(r.status()).isEqualTo(200);
    assertThat(r.body()).isEqualTo("USER:" + ACCOUNT + ":USER");
  }

  @Test
  void publicEndpointAndPublicPaths() throws Exception {
    assertThat(call("/v1/public", null).status()).isEqualTo(200);
    assertThat(call("/_dev/ping", null).status()).isEqualTo(200);
    // A public path does not override explicit requirements.
    assertThat(call("/_dev/admin", null).status()).isEqualTo(401);
  }

  @Test
  void serviceTokensOnlyOnInternalEndpoints() throws Exception {
    String reco = idp.service("reco").audience("ledger").issuedAt(T0).sign();
    String billing = idp.service("billing").audience("ledger").issuedAt(T0).sign();
    assertThat(call("/v1/me", reco).status()).isEqualTo(403);
    HttpResult ok = call("/internal/profile", reco);
    assertThat(ok.status()).isEqualTo(200);
    assertThat(ok.body()).isEqualTo("svc:reco");
    assertThat(call("/internal/profile", billing).status()).isEqualTo(403);
    assertThat(call("/internal/profile", user().sign()).status()).isEqualTo(403);
  }

  @Test
  void permissionsMustAllBePresent() throws Exception {
    String partial = idp.admin(ACCOUNT, "CATALOG_EDITOR").permissions("card.write").audience("ledger").issuedAt(T0).sign();
    HttpResult r = call("/v1/admin/cards", partial);
    assertThat(r.status()).isEqualTo(403);
    assertThat(r.body()).contains("\"code\":\"FORBIDDEN\"").contains("card.publish");
    String full = idp.admin(ACCOUNT, "CATALOG_EDITOR").permissions("card.write", "card.publish")
        .audience("ledger").issuedAt(T0).sign();
    assertThat(call("/v1/admin/cards", full).status()).isEqualTo(200);
    assertThat(call("/v1/admin/cards", user().sign()).status()).isEqualTo(403);
  }

  @Test
  void stepUpNeedsRecentLogin() throws Exception {
    assertThat(call("/v1/me/password", user().authTime(T0.minusSeconds(60)).sign()).status()).isEqualTo(200);
    HttpResult stale = call("/v1/me/password", user().authTime(T0.minus(Duration.ofMinutes(11))).sign());
    assertThat(stale.status()).isEqualTo(401);
    assertThat(stale.body()).contains("\"code\":\"STEP_UP_REQUIRED\"");
    assertThat(stale.headers().get("WWW-Authenticate"))
        .contains("error=\"insufficient_user_authentication\"").contains("max_age=600");
  }

  @Test
  void stepUpNeedsMfaWhenEnrolled() throws Exception {
    HttpResult noMfa = call("/v1/me/password", user().mfaEnrolled(true).amr("pwd").sign());
    assertThat(noMfa.status()).isEqualTo(401);
    assertThat(noMfa.body()).contains("STEP_UP_REQUIRED").contains("\"mfaRequired\":true");
    assertThat(call("/v1/me/password", user().mfaEnrolled(true).amr("pwd", "mfa").sign()).status()).isEqualTo(200);
  }

  @Test
  void jwksOutageIs503WithRetryAfter() throws Exception {
    idp.failJwks(true);
    HttpResult r = call("/v1/me", user().sign());
    assertThat(r.status()).isEqualTo(503);
    assertThat(r.headers().get("Retry-After")).isEqualTo("5");
  }
}
