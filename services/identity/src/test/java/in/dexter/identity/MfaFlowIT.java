package in.dexter.identity;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.identity.support.IdentityIT;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * MASTER_PLAN P05 user MFA: TOTP enrolment, MFA sign-in, step replay rejected, recovery codes single use, challenge
 * burnt after 5 wrong codes, step-up with the second factor, regeneration, and disable (only from an MFA session).
 */
class MfaFlowIT extends IdentityIT {

  @Test
  void enrolSignInStepUpRegenerateDisable() {
    String email = uniqueEmail("mfa");
    String preMfaToken = signUpAndLogin(email).get("accessToken").asString();
    UUID account = accountIdOf(email);
    assertThat(get("/v1/me/mfa", bearer(preMfaToken)).json().get("totpEnrolled").booleanValue()).isFalse();

    // enrol: pending secret, a wrong code does not confirm, the first right code does → 10 recovery codes
    Res enroll = post("/v1/me/mfa/totp/enroll", null, bearer(preMfaToken));
    assertThat(enroll.status()).as(enroll.toString()).isEqualTo(200);
    assertThat(enroll.header("Cache-Control")).contains("no-store");
    String secret = enroll.json().get("secret").asString();
    assertThat(enroll.json().get("otpauthUri").asString()).startsWith("otpauth://totp/").contains("secret=" + secret);
    assertThat(get("/v1/me/mfa", bearer(preMfaToken)).json().get("pending").booleanValue()).isTrue();
    assertThat(post("/v1/me/mfa/totp/confirm", Map.of("code", "12345"), bearer(preMfaToken)).code())
        .isEqualTo("INVALID_MFA_CODE");
    String confirmCode = totp(secret, 0);
    Res confirm = post("/v1/me/mfa/totp/confirm", Map.of("code", confirmCode), bearer(preMfaToken));
    assertThat(confirm.status()).as(confirm.toString()).isEqualTo(200);
    List<String> codes = texts(confirm.json().get("recoveryCodes"));
    assertThat(codes).hasSize(10);
    assertThat(ADMIN.sql("SELECT count(1) FROM identity.recovery_codes WHERE account_id = :a").param("a", account)
        .query(Long.class).single()).isEqualTo(10);
    assertThat(post("/v1/me/mfa/totp/enroll", null, bearer(preMfaToken)).code()).isEqualTo("MFA_ALREADY_ENROLLED");
    assertThat(ADMIN.sql("SELECT position(convert_to(:s, 'UTF8') IN secret_enc) FROM identity.mfa_totp"
            + " WHERE account_id = :a").param("s", secret).param("a", account).query(Integer.class).single())
        .as("secret encrypted at rest").isZero();

    // MFA sign-in: no tokens after the password, a challenge instead
    Res pwd = login(email, PASSWORD);
    assertThat(pwd.status()).isEqualTo(200);
    assertThat(pwd.json().get("mfaRequired").booleanValue()).isTrue();
    assertThat(pwd.json().has("accessToken")).isFalse();
    String challenge = pwd.json().get("mfaToken").asString();
    Res replay = verify(challenge, confirmCode, null);
    assertThat(replay.code()).as("the step used to confirm cannot be replayed").isEqualTo("INVALID_MFA_CODE");
    String nextCode = totp(secret, 1);
    Res ok = verify(challenge, nextCode, null);
    assertThat(ok.status()).as(ok.toString()).isEqualTo(200);
    String mfaToken = ok.json().get("accessToken").asString();
    JsonNode c = claims(mfaToken);
    assertThat(texts(c.get("amr"))).containsExactlyInAnyOrder("pwd", "mfa", "otp");
    assertThat(c.get("mfa_enrolled").booleanValue()).isTrue();
    assertThat(verify(challenge, totp(secret, 1), null).code()).as("challenge single use")
        .isEqualTo("MFA_CHALLENGE_INVALID");

    // same code on a new challenge: replay rejected; then 5 wrong codes burn the challenge
    String c2 = login(email, PASSWORD).json().get("mfaToken").asString();
    assertThat(verify(c2, nextCode, null).code()).isEqualTo("INVALID_MFA_CODE");
    assertThat(verify(c2, "000000", null).code()).isEqualTo("INVALID_MFA_CODE");
    assertThat(verify(c2, "000000", null).code()).isEqualTo("INVALID_MFA_CODE");
    assertThat(verify(c2, "000000", null).code()).isEqualTo("INVALID_MFA_CODE");
    assertThat(verify(c2, "000000", null).code()).as("5th wrong code").isEqualTo("MFA_CHALLENGE_INVALID");
    assertThat(verify(c2, null, codes.get(0)).code()).as("burnt").isEqualTo("MFA_CHALLENGE_INVALID");

    // recovery code: works once
    String c3 = login(email, PASSWORD).json().get("mfaToken").asString();
    Res viaRecovery = verify(c3, null, codes.get(0).toUpperCase(java.util.Locale.ROOT));
    assertThat(viaRecovery.status()).as(viaRecovery.toString()).isEqualTo(200);
    assertThat(texts(claims(viaRecovery.json().get("accessToken").asString()).get("amr")))
        .containsExactlyInAnyOrder("pwd", "mfa");
    String c4 = login(email, PASSWORD).json().get("mfaToken").asString();
    assertThat(verify(c4, null, codes.get(0)).code()).as("recovery code single use").isEqualTo("INVALID_MFA_CODE");
    assertThat(get("/v1/me/mfa", bearer(mfaToken)).json().get("recoveryCodesLeft").asInt()).isEqualTo(9);

    // step-up: password + second factor
    assertThat(post("/v1/auth/step-up", Map.of("password", "Wrong-Password-11", "recoveryCode", codes.get(1)),
        bearer(mfaToken)).code()).isEqualTo("INVALID_CREDENTIALS");
    assertThat(post("/v1/auth/step-up", Map.of("password", PASSWORD), bearer(mfaToken)).code())
        .as("second factor required").isEqualTo("INVALID_MFA_CODE");
    Res stepUp = post("/v1/auth/step-up", Map.of("password", PASSWORD, "recoveryCode", codes.get(1)), bearer(mfaToken));
    assertThat(stepUp.status()).as(stepUp.toString()).isEqualTo(200);
    String fresh = stepUp.json().get("accessToken").asString();
    assertThat(texts(claims(fresh).get("amr"))).contains("mfa");

    // regenerate: old codes stop working
    Res regen = post("/v1/me/mfa/recovery-codes", null, bearer(fresh));
    assertThat(regen.status()).as(regen.toString()).isEqualTo(200);
    List<String> newCodes = texts(regen.json().get("recoveryCodes"));
    assertThat(newCodes).hasSize(10).doesNotContainAnyElementsOf(codes);
    String c5 = login(email, PASSWORD).json().get("mfaToken").asString();
    assertThat(verify(c5, null, codes.get(2)).code()).isEqualTo("INVALID_MFA_CODE");
    assertThat(verify(c5, null, newCodes.get(0)).status()).isEqualTo(200);

    // a token from before enrolment (recent, but without mfa) cannot remove MFA
    Res stale = delete("/v1/me/mfa/totp", bearer(preMfaToken));
    assertThat(stale.status()).isEqualTo(401);
    assertThat(stale.code()).isEqualTo("STEP_UP_REQUIRED");
    assertThat(delete("/v1/me/mfa/totp", bearer(fresh)).status()).isEqualTo(204);
    assertThat(login(email, PASSWORD).json().has("accessToken")).as("plain password sign-in again").isTrue();

    assertThat(alertKinds(account)).containsSubsequence("MFA_ENABLED", "RECOVERY_CODE_USED",
        "RECOVERY_CODES_REGENERATED", "MFA_DISABLED");
  }

  @Test
  void mfaChangesNeedStepUp() {
    String email = uniqueEmail("mfa-stepup");
    String token = signUpAndLogin(email).get("accessToken").asString();
    UUID account = accountIdOf(email);
    // make the sign-in look old: the session and the token's auth_time are what step-up checks
    ADMIN.sql("UPDATE identity.sessions SET auth_time = now() - interval '1 hour' WHERE account_id = :a")
        .param("a", account).update();
    String refresh = signUpAndLoginRefresh(email);
    String oldToken = post("/v1/auth/token/refresh", Map.of("refreshToken", refresh)).json().get("accessToken").asString();
    Res r = post("/v1/me/mfa/totp/enroll", null, bearer(oldToken));
    assertThat(r.status()).isEqualTo(401);
    assertThat(r.code()).isEqualTo("STEP_UP_REQUIRED");
    assertThat(r.header("WWW-Authenticate")).contains("insufficient_user_authentication");
    assertThat(token).isNotBlank();
  }

  /** Logs in again and ages that session too; returns its refresh token. */
  private String signUpAndLoginRefresh(String email) {
    JsonNode t = login(email, PASSWORD).json();
    ADMIN.sql("UPDATE identity.sessions SET auth_time = now() - interval '1 hour' WHERE id = :s")
        .param("s", UUID.fromString(t.get("sessionId").asString())).update();
    return t.get("refreshToken").asString();
  }

  private Res verify(String mfaToken, String code, String recoveryCode) {
    Map<String, Object> body = new java.util.LinkedHashMap<>();
    body.put("mfaToken", mfaToken);
    if (code != null) {
      body.put("code", code);
    }
    if (recoveryCode != null) {
      body.put("recoveryCode", recoveryCode);
    }
    return post("/v1/auth/mfa/verify", body);
  }

  static List<String> texts(JsonNode array) {
    List<String> out = new ArrayList<>();
    array.forEach(n -> out.add(n.asString()));
    return out;
  }
}
