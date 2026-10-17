package in.dexter.identity;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.identity.app.PasswordHasher;
import in.dexter.identity.support.IdentityIT;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * MASTER_PLAN P05 admin console: mandatory TOTP (setup at first sign-in), roles → permissions in the token,
 * audited account lookup, break-glass (eligibility, reason, fresh code, ≤ 1 h, token capped, ended early →
 * refused at once), admin management (invite → accept → sign-in, If-Match updates), refresh reuse detection, and
 * admin/user token separation.
 */
class AdminIT extends IdentityIT {
  static final String ADMIN_PW = "Admin-Console-Secret-77";
  static final String REASON = "Incident INC-42: restore a locked support admin";

  @Autowired
  PasswordHasher hasher;

  /** An active admin with standing roles (inserted as the schema owner would: seed / migration). */
  UUID newAdmin(String email, boolean breakGlass, String... roles) {
    UUID id = UUID.randomUUID();
    ADMIN.sql("INSERT INTO identity.admins (id, email, password_hash, status, break_glass_eligible)"
            + " VALUES (:id, CAST(:e AS citext), :h, 'active', :bg)")
        .param("id", id).param("e", email).param("h", hasher.hash(ADMIN_PW)).param("bg", breakGlass).update();
    for (String r : roles) {
      ADMIN.sql("INSERT INTO identity.admin_roles (admin_id, role) VALUES (:id, :r)").param("id", id).param("r", r)
          .update();
    }
    return id;
  }

  record Console(String access, String refresh, String secret) {}

  /** First sign-in: password → setup (secret) → verify with the first code. */
  Console firstSignIn(String email) {
    Res login = post("/v1/admin/auth/login", Map.of("email", email, "password", ADMIN_PW));
    assertThat(login.status()).as(login.toString()).isEqualTo(200);
    assertThat(login.json().get("setup").booleanValue()).isTrue();
    String challenge = login.json().get("mfaToken").asString();
    Res setup = post("/v1/admin/auth/mfa/setup", Map.of("mfaToken", challenge));
    assertThat(setup.status()).as(setup.toString()).isEqualTo(200);
    String secret = setup.json().get("secret").asString();
    Res verify = post("/v1/admin/auth/mfa/verify", Map.of("mfaToken", challenge, "code", totp(secret, 0)));
    assertThat(verify.status()).as(verify.toString()).isEqualTo(200);
    return new Console(verify.json().get("accessToken").asString(), verify.json().get("refreshToken").asString(),
        secret);
  }

  @Test
  void supportAdminSignsInWithMandatoryMfaAndLooksUpAnAccountAudited() {
    String email = uniqueEmail("support");
    UUID adminId = newAdmin(email, false, "SUPPORT");
    Console c = firstSignIn(email);
    JsonNode claims = claims(c.access());
    assertThat(claims.get("scope").asString()).isEqualTo("admin");
    assertThat(MfaFlowIT.texts(claims.get("roles"))).containsExactly("SUPPORT");
    assertThat(MfaFlowIT.texts(claims.get("perms"))).containsExactlyInAnyOrder("accounts:read", "audit:read");
    assertThat(MfaFlowIT.texts(claims.get("amr"))).contains("mfa");

    Res me = get("/v1/admin/me", bearer(c.access()));
    assertThat(me.status()).as(me.toString()).isEqualTo(200);
    assertThat(me.json().get("elevated").booleanValue()).isFalse();

    // second sign-in: no setup any more, a code is required
    Res login2 = post("/v1/admin/auth/login", Map.of("email", email, "password", ADMIN_PW));
    assertThat(login2.json().get("setup").booleanValue()).isFalse();
    assertThat(post("/v1/admin/auth/mfa/setup", Map.of("mfaToken", login2.json().get("mfaToken").asString())).code())
        .isEqualTo("MFA_ALREADY_ENROLLED");

    String user = uniqueEmail("lookup");
    signUpAndLogin(user);
    UUID userId = accountIdOf(user);
    Res found = get("/v1/admin/accounts?email=" + user, bearer(c.access()));
    assertThat(found.status()).as(found.toString()).isEqualTo(200);
    assertThat(found.json().get("accountId").asString()).isEqualTo(userId.toString());
    assertThat(found.json().get("mfaEnrolled").booleanValue()).isFalse();
    assertThat(get("/v1/admin/accounts?email=" + uniqueEmail("ghost"), bearer(c.access())).status()).isEqualTo(404);
    assertThat(ADMIN.sql("SELECT count(1) FROM identity.admin_audit WHERE admin_id = :a AND action = 'ACCOUNT_LOOKUP'")
        .param("a", adminId).query(Long.class).single()).as("both lookups audited").isEqualTo(2);
    assertThat(outboxRows(userId, "identity.admin.action")).isEqualTo(1);

    // SUPPORT may not manage admins
    Res list = get("/v1/admin/admins", bearer(c.access()));
    assertThat(list.status()).isEqualTo(403);
    assertThat(list.json().get("missingPermissions").get(0).asString()).isEqualTo("admins:read");
    // break-glass needs eligibility
    assertThat(post("/v1/admin/elevations", Map.of("reason", REASON, "code", totp(c.secret(), 1)),
        bearer(c.access())).code()).isEqualTo("NOT_BREAK_GLASS_ELIGIBLE");
  }

  @Test
  void adminAndUserTokensAreSeparate() {
    String email = uniqueEmail("sep");
    newAdmin(email, false, "SUPPORT");
    Console c = firstSignIn(email);
    assertThat(get("/v1/me", bearer(c.access())).status()).as("admin token on a user endpoint").isEqualTo(403);
    String userToken = signUpAndLogin(uniqueEmail("sep-user")).get("accessToken").asString();
    assertThat(get("/v1/admin/me", bearer(userToken)).status()).as("user token on the admin console").isEqualTo(403);
    assertThat(get("/v1/admin/accounts?email=a@b.c", bearer(userToken)).status()).isEqualTo(403);
    assertThat(get("/v1/admin/me").status()).isEqualTo(401);
    assertThat(post("/v1/admin/auth/login", Map.of("email", email, "password", "Wrong-Password-99")).code())
        .isEqualTo("INVALID_CREDENTIALS");
    assertThat(post("/v1/admin/auth/login", Map.of("email", uniqueEmail("nobody"), "password", ADMIN_PW)).code())
        .as("unknown admin: same answer").isEqualTo("INVALID_CREDENTIALS");
  }

  @Test
  void adminRefreshRotatesAndDetectsReuse() {
    String email = uniqueEmail("refresh");
    newAdmin(email, false, "FINANCE");
    Console c = firstSignIn(email);
    Res r1 = post("/v1/admin/auth/token/refresh", Map.of("refreshToken", c.refresh()));
    assertThat(r1.status()).as(r1.toString()).isEqualTo(200);
    assertThat(MfaFlowIT.texts(claims(r1.json().get("accessToken").asString()).get("perms")))
        .containsExactlyInAnyOrder("accounts:read", "billing:read", "billing:refund");
    Res reuse = post("/v1/admin/auth/token/refresh", Map.of("refreshToken", c.refresh()));
    assertThat(reuse.code()).isEqualTo("REFRESH_TOKEN_REUSED");
    assertThat(post("/v1/admin/auth/token/refresh", Map.of("refreshToken", r1.json().get("refreshToken").asString()))
        .code()).as("whole family revoked").isEqualTo("INVALID_REFRESH_TOKEN");
    assertThat(post("/v1/auth/token/refresh", Map.of("refreshToken", c.refresh())).code())
        .as("admin refresh tokens are not user refresh tokens").isEqualTo("INVALID_REFRESH_TOKEN");
  }

  @Test
  void breakGlassManagesAdminsAndEndsAtOnce() {
    String email = uniqueEmail("glass");
    UUID adminId = newAdmin(email, true, "SUPPORT");
    Console c = firstSignIn(email);
    assertThat(get("/v1/admin/admins", bearer(c.access())).status()).isEqualTo(403);

    assertThat(post("/v1/admin/elevations", Map.of("reason", "too short", "code", totp(c.secret(), 1)),
        bearer(c.access())).code()).isEqualTo("VALIDATION_FAILED");
    assertThat(post("/v1/admin/elevations", Map.of("reason", REASON, "code", "000000"), bearer(c.access())).code())
        .isEqualTo("INVALID_MFA_CODE");
    Res up = post("/v1/admin/elevations", Map.of("reason", REASON, "code", totp(c.secret(), 1)), bearer(c.access()));
    assertThat(up.status()).as(up.toString()).isEqualTo(200);
    String superToken = up.json().get("accessToken").asString();
    Instant until = Instant.parse(up.json().get("expiresAt").asString());
    assertThat(until).isBefore(Instant.now().plusSeconds(3601));
    JsonNode sc = claims(superToken);
    assertThat(MfaFlowIT.texts(sc.get("roles"))).contains("SUPER_ADMIN", "SUPPORT");
    assertThat(MfaFlowIT.texts(sc.get("perms"))).contains("admins:read", "admins:write");
    assertThat(sc.get("exp").asLong()).as("token never outlives the elevation").isLessThanOrEqualTo(until.getEpochSecond());
    assertThat(ADMIN.sql("SELECT reason FROM identity.elevations WHERE admin_id = :a").param("a", adminId)
        .query(String.class).single()).isEqualTo(REASON);
    assertThat(post("/v1/admin/elevations", Map.of("reason", REASON, "code", totp(c.secret(), 1)),
        bearer(superToken)).status()).as("one active elevation at a time").isIn(401, 409);

    // invite → accept → first sign-in of the new admin
    String invitee = uniqueEmail("invitee");
    Res created = post("/v1/admin/admins", Map.of("email", invitee, "displayName", "New Support",
        "roles", List.of("SUPPORT")), bearer(superToken));
    assertThat(created.status()).as(created.toString()).isEqualTo(201);
    assertThat(created.json().get("status").asString()).isEqualTo("invited");
    UUID inviteeId = UUID.fromString(created.json().get("id").asString());
    assertThat(post("/v1/admin/admins", Map.of("email", invitee), bearer(superToken)).code())
        .isEqualTo("ADMIN_EMAIL_TAKEN");
    assertThat(post("/v1/admin/admins", Map.of("email", uniqueEmail("x"), "roles", List.of("SUPER_ADMIN")),
        bearer(superToken)).code()).as("SUPER_ADMIN is never standing").isEqualTo("VALIDATION_FAILED");
    String inviteToken = awaitInviteToken(invitee);
    assertThat(post("/v1/admin/auth/invite/accept", Map.of("token", inviteToken, "password", ADMIN_PW)).status())
        .isEqualTo(200);
    assertThat(post("/v1/admin/auth/invite/accept", Map.of("token", inviteToken, "password", ADMIN_PW)).code())
        .as("single use").isEqualTo("INVALID_TOKEN");
    firstSignIn(invitee);

    Res page = get("/v1/admin/admins?limit=2", bearer(superToken));
    assertThat(page.status()).as(page.toString()).isEqualTo(200);
    assertThat(page.json().get("data").size()).isEqualTo(2);

    // If-Match update: disable the invitee (revokes its sessions), stale version → 412, self-change refused
    Res one = get("/v1/admin/admins/" + inviteeId, bearer(superToken));
    String etag = one.header("ETag");
    Res disabled = send("PATCH", "/v1/admin/admins/" + inviteeId, "{\"status\":\"disabled\"}", "application/json",
        bearer(superToken, "If-Match", etag));
    assertThat(disabled.status()).as(disabled.toString()).isEqualTo(200);
    assertThat(disabled.json().get("status").asString()).isEqualTo("disabled");
    assertThat(send("PATCH", "/v1/admin/admins/" + inviteeId, "{\"status\":\"active\"}", "application/json",
        bearer(superToken, "If-Match", etag)).status()).isEqualTo(412);
    assertThat(ADMIN.sql("SELECT count(1) FROM identity.admin_sessions WHERE admin_id = :a AND revoked_at IS NULL")
        .param("a", inviteeId).query(Long.class).single()).isZero();
    Res self = send("PATCH", "/v1/admin/admins/" + adminId, "{\"roles\":[\"FINANCE\"]}", "application/json",
        bearer(superToken, "If-Match", "\"0\""));
    assertThat(self.code()).isEqualTo("SELF_CHANGE_FORBIDDEN");

    // ending the elevation: the SUPER_ADMIN token still verifies but is refused at once
    Res end = delete("/v1/admin/elevations/current", bearer(superToken));
    assertThat(end.status()).as(end.toString()).isEqualTo(200);
    assertThat(end.json().get("ended").booleanValue()).isTrue();
    assertThat(MfaFlowIT.texts(claims(end.json().get("accessToken").asString()).get("roles")))
        .doesNotContain("SUPER_ADMIN");
    assertThat(get("/v1/admin/admins", bearer(superToken)).status()).isEqualTo(403);
    assertThat(ADMIN.sql("SELECT action FROM identity.admin_audit WHERE admin_id = :a ORDER BY occurred_at")
        .param("a", adminId).query(String.class).list())
        .containsSubsequence("ADMIN_SIGNED_IN", "BREAK_GLASS_STARTED", "ADMIN_CREATED", "ADMIN_UPDATED",
            "BREAK_GLASS_ENDED");
  }

  @Test
  void breakGlassExpires() {
    String email = uniqueEmail("expiry");
    UUID adminId = newAdmin(email, true, "CATALOG_EDITOR");
    Console c = firstSignIn(email);
    Res up = post("/v1/admin/elevations", Map.of("reason", REASON, "code", totp(c.secret(), 1)), bearer(c.access()));
    assertThat(up.status()).as(up.toString()).isEqualTo(200);
    String superToken = up.json().get("accessToken").asString();
    assertThat(get("/v1/admin/admins", bearer(superToken)).status()).isEqualTo(200);
    // the hour passes (moved in the database; the token itself is still within its 10 minutes)
    ADMIN.sql("UPDATE identity.elevations SET starts_at = starts_at - interval '2 hours',"
        + " expires_at = expires_at - interval '2 hours' WHERE admin_id = :a").param("a", adminId).update();
    assertThat(get("/v1/admin/admins", bearer(superToken)).status()).isEqualTo(403);
    Res refreshed = post("/v1/admin/auth/token/refresh", Map.of("refreshToken", c.refresh()));
    assertThat(MfaFlowIT.texts(claims(refreshed.json().get("accessToken").asString()).get("roles")))
        .as("refresh after expiry: no SUPER_ADMIN").containsExactly("CATALOG_EDITOR");
  }

  @Test
  void databaseRefusesElevationForIneligibleAdmins() {
    UUID id = newAdmin(uniqueEmail("db-elev"), false, "SUPPORT");
    var db = in.dexter.testkit.containers.TestPostgres.flywayDb("identity");
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> db.exec("app",
        "BEGIN; SELECT set_config('app.account_id', '" + id + "', true);"
            + " INSERT INTO identity.elevations (id, admin_id, reason, starts_at, expires_at) VALUES (gen_random_uuid(),"
            + " '" + id + "', '" + REASON + "', now(), now() + interval '30 minutes'); COMMIT"))
        .hasMessageContaining("row-level security");
  }

  String awaitInviteToken(String to) {
    long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
    java.util.regex.Pattern p = java.util.regex.Pattern.compile("#invite=([A-Za-z0-9_-]{43})");
    while (System.nanoTime() < deadline) {
      for (var m : mail.sent()) {
        if (m.to().equals(to)) {
          var x = p.matcher(m.text());
          if (x.find()) {
            return x.group(1);
          }
        }
      }
      sleep(25);
    }
    throw new AssertionError("no invite mail to " + to);
  }
}
