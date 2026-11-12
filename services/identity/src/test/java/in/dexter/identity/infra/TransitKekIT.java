package in.dexter.identity.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.dexter.identity.app.KeyCrypto;
import in.dexter.identity.app.SigningKeyStore.KeyRow;
import in.dexter.identity.app.TokenClaimsSpec;
import in.dexter.testkit.containers.TestVault;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * P19 (ADR-0026): the identity KEK in Vault Transit, against a real Vault (dev server). The token the tests use has
 * only the policy the launcher gives identity (encrypt/decrypt on transit/dexter-kek), so least privilege is proved
 * with the same rules.
 */
class TransitKekIT {
  static final String KEY = "dexter-kek";
  static String token;

  @BeforeAll
  static void vault() {
    TestVault.root("POST", "sys/mounts/transit", "{\"type\":\"transit\"}");
    TestVault.root("POST", "transit/keys/" + KEY, "{\"type\":\"aes256-gcm96\"}");
    TestVault.root("POST", "transit/keys/other-key", "{\"type\":\"aes256-gcm96\"}");
    TestVault.root("PUT", "sys/policies/acl/dexter-identity", "{\"policy\":"
        + "\"path \\\"transit/encrypt/" + KEY + "\\\" { capabilities = [\\\"update\\\"] }\\n"
        + "path \\\"transit/decrypt/" + KEY + "\\\" { capabilities = [\\\"update\\\"] }\"}");
    String created = TestVault.root("POST", "auth/token/create",
        "{\"policies\":[\"dexter-identity\"],\"ttl\":\"1h\",\"no_default_policy\":true}");
    Matcher m = Pattern.compile("\"client_token\":\"([^\"]+)\"").matcher(created);
    assertThat(m.find()).isTrue();
    token = m.group(1);
  }

  static TransitKek transit(String key) {
    return new TransitKek(TestVault.address(), key, () -> token, Duration.ofSeconds(5));
  }

  static byte[] aad(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }

  @Test
  void wrapsInVaultAndUnwrapsWithTheSameAssociatedDataOnly() {
    KekRing ring = new KekRing("transit:" + KEY, Map.of(), transit(KEY));
    byte[] dek = CryptoTest.key();
    byte[] sealed = ring.wrap(aad("dek:account-a"), dek);
    assertThat(new String(sealed, StandardCharsets.US_ASCII)).startsWith("vault:v1:");
    assertThat(ring.activeId()).isEqualTo("transit:dexter-kek");
    assertThat(ring.unwrap("transit:" + KEY, aad("dek:account-a"), sealed)).containsExactly(dek);
    // a wrapped DEK copied to another account's row does not open (Vault authenticates the associated data)
    assertThatThrownBy(() -> ring.unwrap("transit:" + KEY, aad("dek:account-b"), sealed))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("HTTP 400");
  }

  @Test
  void dataWrappedUnderTheLocalKekStillOpensAfterTheMoveToTransit() {
    byte[] k1 = CryptoTest.key();
    KekRing before = new KekRing("k1", Map.of("k1", k1));
    byte[] old = before.wrap(aad("dek:x"), new byte[] {7, 7});
    KekRing after = new KekRing("transit:" + KEY, Map.of("k1", k1), transit(KEY));
    assertThat(after.unwrap("k1", aad("dek:x"), old)).containsExactly(7, 7);
    byte[] fresh = after.wrap(aad("dek:y"), new byte[] {8});
    assertThat(after.unwrap(after.activeId(), aad("dek:y"), fresh)).containsExactly(8);
    // a process without Vault configured cannot open Transit-wrapped data - and says why
    assertThatThrownBy(() -> before.unwrap("transit:" + KEY, aad("dek:y"), fresh))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("Vault Transit");
    // a local id can never shadow a Transit one
    assertThatThrownBy(() -> new KekRing("k1", Map.of("transit:x", k1))).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aRotatedTransitKeyStillOpensOldCiphertextAndRewrapsToTheNewVersion() {
    TestVault.root("POST", "transit/keys/rot-key", "{\"type\":\"aes256-gcm96\"}");
    TestVault.root("PUT", "sys/policies/acl/rot", "{\"policy\":"
        + "\"path \\\"transit/+/rot-key\\\" { capabilities = [\\\"update\\\"] }\"}");
    String created = TestVault.root("POST", "auth/token/create", "{\"policies\":[\"rot\"],\"ttl\":\"1h\"}");
    Matcher m = Pattern.compile("\"client_token\":\"([^\"]+)\"").matcher(created);
    assertThat(m.find()).isTrue();
    String rotToken = m.group(1);
    TransitKek t = new TransitKek(TestVault.address(), "rot-key", () -> rotToken, Duration.ofSeconds(5));
    byte[] v1 = t.wrap(aad("a"), new byte[] {1});
    TestVault.root("POST", "transit/keys/rot-key/rotate", null);
    byte[] v2 = t.wrap(aad("a"), new byte[] {2});
    assertThat(new String(v1, StandardCharsets.US_ASCII)).startsWith("vault:v1:");
    assertThat(new String(v2, StandardCharsets.US_ASCII)).startsWith("vault:v2:");
    assertThat(t.unwrap(aad("a"), v1)).containsExactly(1);
    // Vault's own transit/rewrap takes no associated data, so it cannot re-wrap AAD-bound ciphertext (this is why the
    // KEK re-wrap job unwraps and wraps in identity: ADR-0026 §4, docs/runbooks/key-rotation.md)
    assertThat(TestVault.api(TestVault.ROOT_TOKEN, "POST", "transit/rewrap/rot-key", "{\"ciphertext\":\""
        + new String(v1, StandardCharsets.US_ASCII) + "\"}").statusCode()).isEqualTo(400);
    // unwrap + wrap moves v1 ciphertext to the latest version, still bound to the same associated data
    byte[] moved = t.wrap(aad("a"), t.unwrap(aad("a"), v1));
    assertThat(new String(moved, StandardCharsets.US_ASCII)).startsWith("vault:v2:");
    assertThat(t.unwrap(aad("a"), moved)).containsExactly(1);
  }

  @Test
  void identitysTokenCanUseOnlyItsOwnKey() {
    assertThatThrownBy(() -> transit("other-key").wrap(aad("a"), new byte[] {1}))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("HTTP 403");
    assertThat(TestVault.api(token, "GET", "secret/data/dexter/ledger", null).statusCode()).isEqualTo(403);
    assertThat(TestVault.api(token, "POST", "transit/keys/" + KEY + "/rotate", "{}").statusCode()).isEqualTo(403);
  }

  @Test
  void signingKeysWrappedInTransitSignTokens() throws Exception {
    JoseKeyCrypto crypto = new JoseKeyCrypto(new KekRing("transit:" + KEY, Map.of(), transit(KEY)));
    KeyCrypto.NewKey k = crypto.generate();
    assertThat(k.kekId()).isEqualTo("transit:" + KEY);
    KeyRow row = new KeyRow(k.kid(), k.publicJwk(), k.privateEnc(), k.kekId(), "active", Instant.now(), Instant.now(),
        null);
    Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    String jwt = crypto.sign(row, new TokenClaimsSpec("svc:ledger", List.of("catalog"), "ledger", null, "service", null,
        null, null, null, null, null, Duration.ofMinutes(5), "https://issuer.test", now, UUID.randomUUID().toString(),
        null));
    assertThat(jwt.split("\\.")).hasSize(3);
  }
}
