package in.dexter.identity;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.identity.support.IdentityIT;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** MASTER_PLAN P04 "full auth flow": register → verify → login → /me → refresh → logout, plus password flows. */
class AuthFlowIT extends IdentityIT {

  @Test
  void registerVerifyLoginRefreshLogout() {
    String email = uniqueEmail("flow");
    Res reg = register(email, PASSWORD);
    assertThat(reg.status()).isEqualTo(202);
    assertThat(reg.json().get("status").asString()).isEqualTo("accepted");
    UUID account = accountIdOf(email);
    assertThat(outboxRows(account, "identity.account.registered")).isEqualTo(1);

    Res early = login(email, PASSWORD);
    assertThat(early.status()).isEqualTo(403);
    assertThat(early.code()).isEqualTo("EMAIL_NOT_VERIFIED");

    String token = awaitMailToken(email, "Verify");
    assertThat(post("/v1/auth/verify-email", Map.of("token", token)).json().get("status").asString())
        .isEqualTo("verified");
    Res again = post("/v1/auth/verify-email", Map.of("token", token));
    assertThat(again.status()).as("single use").isEqualTo(400);
    assertThat(again.code()).isEqualTo("INVALID_TOKEN");
    assertThat(outboxRows(account, "identity.account.verified")).isEqualTo(1);

    Res login = login(email, PASSWORD);
    assertThat(login.status()).as(login.toString()).isEqualTo(200);
    assertThat(login.header("Cache-Control")).contains("no-store");
    JsonNode t = login.json();
    assertThat(t.get("tokenType").asString()).isEqualTo("Bearer");
    assertThat(t.get("expiresIn").asLong()).isEqualTo(600);
    String access = t.get("accessToken").asString();
    String refresh = t.get("refreshToken").asString();
    assertThat(refresh).hasSize(43);
    assertThat(ADMIN.sql("SELECT count(1) FROM identity.refresh_tokens WHERE token_hash = sha256(convert_to(:t, 'UTF8'))")
        .param("t", refresh).query(Long.class).single()).as("only the hash is stored").isEqualTo(1);

    Res me = get("/v1/me", bearer(access));
    assertThat(me.status()).isEqualTo(200);
    assertThat(me.json().get("email").asString()).isEqualTo(email);
    assertThat(me.json().get("accountId").asString()).isEqualTo(account.toString());
    assertThat(me.json().get("emailVerified").booleanValue()).isTrue();
    assertThat(me.header("ETag")).isEqualTo("\"1\"");

    Res r1 = post("/v1/auth/token/refresh", Map.of("refreshToken", refresh));
    assertThat(r1.status()).as(r1.toString()).isEqualTo(200);
    String refresh2 = r1.json().get("refreshToken").asString();
    assertThat(refresh2).isNotEqualTo(refresh);
    assertThat(r1.json().get("sessionId").asString()).isEqualTo(t.get("sessionId").asString());
    assertThat(get("/v1/me", bearer(r1.json().get("accessToken").asString())).status()).isEqualTo(200);

    Res sessions = get("/v1/me/sessions", bearer(access));
    assertThat(sessions.status()).isEqualTo(200);
    assertThat(sessions.json().get("data").size()).isEqualTo(1);
    assertThat(sessions.json().get("data").get(0).get("current").booleanValue()).isTrue();

    assertThat(post("/v1/auth/logout", null, bearer(access)).status()).isEqualTo(204);
    Res afterLogout = post("/v1/auth/token/refresh", Map.of("refreshToken", refresh2));
    assertThat(afterLogout.status()).isEqualTo(401);
    assertThat(afterLogout.code()).isEqualTo("INVALID_REFRESH_TOKEN");
    assertThat(outboxRows(account, "identity.session.revoked")).isEqualTo(1);
  }

  @Test
  void reusingARotatedRefreshTokenRevokesTheWholeFamily() {
    String email = uniqueEmail("reuse");
    JsonNode t = signUpAndLogin(email);
    UUID account = accountIdOf(email);
    String old = t.get("refreshToken").asString();
    Res rotated = post("/v1/auth/token/refresh", Map.of("refreshToken", old));
    assertThat(rotated.status()).isEqualTo(200);
    String current = rotated.json().get("refreshToken").asString();

    Res replay = post("/v1/auth/token/refresh", Map.of("refreshToken", old));
    assertThat(replay.status()).isEqualTo(401);
    assertThat(replay.code()).isEqualTo("REFRESH_TOKEN_REUSED");

    // the legitimate holder's newest token dies with the family
    Res legit = post("/v1/auth/token/refresh", Map.of("refreshToken", current));
    assertThat(legit.status()).isEqualTo(401);
    assertThat(legit.code()).isEqualTo("INVALID_REFRESH_TOKEN");
    assertThat(ADMIN.sql("SELECT count(1) FROM identity.refresh_tokens WHERE account_id = :a AND revoked_at IS NULL")
        .param("a", account).query(Long.class).single()).isZero();
    assertThat(ADMIN.sql("SELECT revoke_reason FROM identity.sessions WHERE account_id = :a").param("a", account)
        .query(String.class).single()).isEqualTo("refresh_reuse");
    assertThat(outboxRows(account, "identity.security.alert")).isEqualTo(1);
    assertThat(outboxRows(account, "identity.session.revoked")).isEqualTo(1);
    // a replay of the replay changes nothing more
    assertThat(post("/v1/auth/token/refresh", Map.of("refreshToken", old)).status()).isEqualTo(401);
    assertThat(outboxRows(account, "identity.security.alert")).isEqualTo(1);
  }

  @Test
  void garbageRefreshTokensAre401() {
    for (String bad : List.of("", "short", "x".repeat(43), "!".repeat(43))) {
      Res r = post("/v1/auth/token/refresh", Map.of("refreshToken", bad));
      assertThat(r.status()).as(bad).isEqualTo(401);
      assertThat(r.code()).isEqualTo("INVALID_REFRESH_TOKEN");
    }
  }

  @Test
  void passwordChangeNeedsStepUpAndSignsOtherSessionsOut() {
    String email = uniqueEmail("change");
    JsonNode s1 = signUpAndLogin(email);
    JsonNode s2 = login(email, PASSWORD).json();
    String access1 = s1.get("accessToken").asString();
    Res wrong = post("/v1/auth/password/change", Map.of("currentPassword", "nope-nope-nope", "newPassword",
        "Another-Long-Secret-7"), bearer(access1));
    assertThat(wrong.status()).isEqualTo(403);
    assertThat(wrong.code()).isEqualTo("CURRENT_PASSWORD_INVALID");
    Res weak = post("/v1/auth/password/change", Map.of("currentPassword", PASSWORD, "newPassword", "password123"),
        bearer(access1));
    assertThat(weak.status()).isEqualTo(400);
    assertThat(weak.body()).contains("BREACHED");

    Res ok = post("/v1/auth/password/change", Map.of("currentPassword", PASSWORD, "newPassword",
        "Another-Long-Secret-7"), bearer(access1));
    assertThat(ok.status()).as(ok.toString()).isEqualTo(204);
    assertThat(post("/v1/auth/token/refresh", Map.of("refreshToken", s2.get("refreshToken").asString())).status())
        .as("other session signed out").isEqualTo(401);
    assertThat(post("/v1/auth/token/refresh", Map.of("refreshToken", s1.get("refreshToken").asString())).status())
        .as("this session kept").isEqualTo(200);
    assertThat(login(email, PASSWORD).status()).isEqualTo(401);
    assertThat(login(email, "Another-Long-Secret-7").status()).isEqualTo(200);
    assertThat(awaitMail(email, "password was changed", java.time.Duration.ofSeconds(5))).isTrue();
  }

  @Test
  void forgotAndResetSignsEverySessionOut() {
    String email = uniqueEmail("reset");
    JsonNode s1 = signUpAndLogin(email);
    UUID account = accountIdOf(email);
    assertThat(post("/v1/auth/password/forgot", Map.of("email", email)).status()).isEqualTo(202);
    String token = awaitMailToken(email, "Reset");
    Res weak = post("/v1/auth/password/reset", Map.of("token", token, "newPassword", "short"));
    assertThat(weak.status()).isEqualTo(400);
    Res ok = post("/v1/auth/password/reset", Map.of("token", token, "newPassword", "Brand-New-Secret-42"));
    assertThat(ok.status()).as(ok.toString()).isEqualTo(200);
    assertThat(post("/v1/auth/password/reset", Map.of("token", token, "newPassword", "Brand-New-Secret-43")).code())
        .as("single use").isEqualTo("INVALID_TOKEN");
    assertThat(post("/v1/auth/token/refresh", Map.of("refreshToken", s1.get("refreshToken").asString())).status())
        .isEqualTo(401);
    assertThat(login(email, "Brand-New-Secret-42").status()).isEqualTo(200);
    assertThat(outboxRows(account, "identity.session.revoked")).isEqualTo(1);
  }

  @Test
  void logoutAllRevokesEverySession() {
    String email = uniqueEmail("all");
    JsonNode s1 = signUpAndLogin(email);
    login(email, PASSWORD);
    login(email, PASSWORD);
    Res r = post("/v1/auth/logout-all", null, bearer(s1.get("accessToken").asString()));
    assertThat(r.status()).as(r.toString()).isEqualTo(200);
    assertThat(r.json().get("sessionsRevoked").asInt()).isEqualTo(3);
    assertThat(ADMIN.sql("SELECT count(1) FROM identity.sessions WHERE account_id = :a AND revoked_at IS NULL")
        .param("a", accountIdOf(email)).query(Long.class).single()).isZero();
  }

  @Test
  void protectedEndpointsNeedAToken() {
    assertThat(get("/v1/me").status()).isEqualTo(401);
    assertThat(get("/v1/me").header("WWW-Authenticate")).startsWith("Bearer");
    assertThat(get("/v1/me", bearer("not.a.jwt")).status()).isEqualTo(401);
    assertThat(post("/v1/auth/logout", null).status()).isEqualTo(401);
  }
}
