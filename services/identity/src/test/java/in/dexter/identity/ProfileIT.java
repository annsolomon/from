package in.dexter.identity;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.identity.support.IdentityIT;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** GET/PATCH /v1/me: ETag + If-Match (MASTER_PLAN 3.4, 9), merge-patch semantics, phone encrypted at rest (7.4). */
class ProfileIT extends IdentityIT {
  static final String MERGE = "application/merge-patch+json";

  Res patch(String token, String ifMatch, String json) {
    return ifMatch == null ? send("PATCH", "/v1/me", json, MERGE, bearer(token))
        : send("PATCH", "/v1/me", json, MERGE, bearer(token, "If-Match", ifMatch));
  }

  @Test
  void patchWithIfMatchEncryptsThePhoneAndDetectsStaleVersions() {
    String email = uniqueEmail("profile");
    String token = signUpAndLogin(email).get("accessToken").asString();
    UUID account = accountIdOf(email);
    String etag = get("/v1/me", bearer(token)).header("ETag");

    assertThat(patch(token, null, "{\"displayName\":\"Asha K\"}").status()).isEqualTo(428);
    assertThat(patch(token, "W/\"1\"", "{\"displayName\":\"Asha K\"}").code()).isEqualTo("IF_MATCH_INVALID");

    Res ok = patch(token, etag, "{\"displayName\":\"Asha K\",\"tz\":\"Asia/Dubai\",\"phone\":\"98765 43210\"}");
    assertThat(ok.status()).as(ok.toString()).isEqualTo(200);
    JsonNode me = ok.json();
    assertThat(me.get("displayName").asString()).isEqualTo("Asha K");
    assertThat(me.get("tz").asString()).isEqualTo("Asia/Dubai");
    assertThat(me.get("phone").asString()).isEqualTo("+919876543210");
    assertThat(ok.header("ETag")).isNotEqualTo(etag);

    byte[] stored = ADMIN.sql("SELECT phone_enc FROM identity.accounts WHERE id = :a").param("a", account)
        .query(byte[].class).single();
    assertThat(new String(stored, java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("9876543210");
    assertThat(ADMIN.sql("SELECT count(1) FROM identity.account_keys WHERE account_id = :a").param("a", account)
        .query(Long.class).single()).as("per-account data key created").isEqualTo(1);

    Res stale = patch(token, etag, "{\"displayName\":\"Again\"}");
    assertThat(stale.status()).isEqualTo(412);
    assertThat(stale.code()).isEqualTo("VERSION_CONFLICT");

    Res cleared = patch(token, ok.header("ETag"), "{\"phone\":null,\"displayName\":null}");
    assertThat(cleared.status()).isEqualTo(200);
    assertThat(cleared.json().get("phone").isNull()).isTrue();
    assertThat(cleared.json().get("displayName").isNull()).isTrue();
    assertThat(cleared.json().get("tz").asString()).as("absent member unchanged").isEqualTo("Asia/Dubai");
  }

  @Test
  void validationAndUniqueness() {
    String a = signUpAndLogin(uniqueEmail("pa")).get("accessToken").asString();
    String b = signUpAndLogin(uniqueEmail("pb")).get("accessToken").asString();
    Res bad = patch(a, "\"1\"", "{\"tz\":\"Mars/Base\",\"phone\":\"12\",\"displayName\":\"\"}");
    assertThat(bad.status()).isEqualTo(400);
    assertThat(bad.body()).contains("INVALID_TZ", "INVALID_PHONE", "INVALID_LENGTH");
    assertThat(patch(a, "\"1\"", "{\"email\":\"x@y.z\"}").code()).isEqualTo("UNKNOWN_FIELD");

    assertThat(patch(a, "\"1\"", "{\"phone\":\"+919812345678\"}").status()).isEqualTo(200);
    Res taken = patch(b, "\"1\"", "{\"phone\":\"+91 98123 45678\"}");
    assertThat(taken.status()).isEqualTo(409);
    assertThat(taken.code()).isEqualTo("PHONE_TAKEN");
  }
}
