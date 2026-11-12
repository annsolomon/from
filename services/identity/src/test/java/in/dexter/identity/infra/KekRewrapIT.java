package in.dexter.identity.infra;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.db.tenant.TenantTx;
import in.dexter.identity.app.KekRewrapService;
import in.dexter.identity.app.KekRewrapStore;
import in.dexter.identity.domain.IdentityTenants;
import in.dexter.identity.support.IdentityIT;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * P19 (ADR-0026): the KEK re-wrap against the real schema. Rows are wrapped under a retiring KEK {@code k9} that only
 * this test knows, re-wrapped to the context's active {@code k1} through the SECURITY DEFINER functions, and must then
 * open with the context's own ring under the same associated data. Only {@code k9} rows are touched ({@code from}), so
 * the shared IT database stays valid for every other context.
 */
class KekRewrapIT extends IdentityIT {
  @Autowired TenantTx tx;
  @Autowired KekRewrapStore store;
  @Autowired KekRing contextRing;
  @Autowired KekRewrapService contextService;

  static byte[] k1() {
    return Base64.getDecoder().decode(KEK);
  }

  UUID account(byte[] dek, KekRing retiring) {
    UUID id = UUID.randomUUID();
    ADMIN.sql("INSERT INTO identity.accounts (id, email, password_hash, status, tz, display_name)"
            + " VALUES (:id, :e, '$argon2id$v=19$m=65536,t=3,p=1$not-a-real-hash', 'active', 'Asia/Kolkata', 'kek')")
        .param("id", id).param("e", uniqueEmail("kek")).update();
    ADMIN.sql("INSERT INTO identity.account_keys (account_id, dek_enc, kek_id) VALUES (:id, :enc, 'k9')")
        .param("id", id).param("enc", retiring.wrap(("dek:" + id).getBytes(StandardCharsets.UTF_8), dek)).update();
    return id;
  }

  String kekOf(UUID id) {
    return ADMIN.sql("SELECT kek_id FROM identity.account_keys WHERE account_id = :id").param("id", id)
        .query(String.class).single();
  }

  @Test
  void keysUnderARetiringKekMoveToTheActiveOneAndStillOpen() {
    byte[] k9 = CryptoTest.key();
    KekRing retiring = new KekRing("k9", Map.of("k9", k9));
    byte[] dekA = CryptoTest.key();
    byte[] dekB = CryptoTest.key();
    UUID a = account(dekA, retiring);
    UUID b = account(dekB, retiring);
    List<KekRewrapStore.Wrapped> stale = tx.read(IdentityTenants.SYSTEM, () -> store.stale("k1", "k9", 100));
    assertThat(stale).extracting(KekRewrapStore.Wrapped::id).contains(a.toString(), b.toString());
    assertThat(contextService.usage()).anySatisfy(u -> {
      assertThat(u.kind()).isEqualTo("account_key");
      assertThat(u.kekId()).isEqualTo("k9");
      assertThat(u.rows()).isGreaterThanOrEqualTo(2);
    });

    // the operator's ring during rotation: active k1, the retiring k9 still configured for unwrapping
    KekRing during = new KekRing("k1", Map.of("k1", k1(), "k9", k9));
    KekRewrapService service = new KekRewrapService(tx, store, new KekRingRewrapper(during));
    KekRewrapService.Result r = service.run("k9", 1); // batch of 1: the loop must keep going
    assertThat(r.rewrapped()).isGreaterThanOrEqualTo(2);
    assertThat(r.failed()).isZero();
    assertThat(kekOf(a)).isEqualTo("k1");
    assertThat(kekOf(b)).isEqualTo("k1");
    assertThat(tx.read(IdentityTenants.SYSTEM, () -> store.stale("k1", "k9", 100))).isEmpty();

    // the context's ring (k1 only, no k9) now opens both DEKs under their own account's associated data
    byte[] sealedA = ADMIN.sql("SELECT dek_enc FROM identity.account_keys WHERE account_id = :id").param("id", a)
        .query(byte[].class).single();
    assertThat(contextRing.unwrap("k1", ("dek:" + a).getBytes(StandardCharsets.UTF_8), sealedA)).containsExactly(dekA);
    // a second pass finds nothing to do
    assertThat(service.run("k9", 10).rewrapped()).isZero();
  }

  @Test
  void theSwapIsCompareAndSet() {
    byte[] k9 = CryptoTest.key();
    UUID a = account(CryptoTest.key(), new KekRing("k9", Map.of("k9", k9)));
    KekRewrapStore.Wrapped stale = tx.read(IdentityTenants.SYSTEM, () -> store.stale("k1", "k9", 100)).stream()
        .filter(w -> w.id().equals(a.toString())).findFirst().orElseThrow();
    // someone else moved it first (another instance, a rotation): this swap must not overwrite
    ADMIN.sql("UPDATE identity.account_keys SET kek_id = 'k8' WHERE account_id = :id").param("id", a).update();
    assertThat(tx.write(IdentityTenants.SYSTEM, () -> store.swap(stale, "k1", new byte[] {1, 2, 3}))).isFalse();
    assertThat(kekOf(a)).isEqualTo("k8");
    // a row that cannot be unwrapped (k8 is unknown to this ring) is reported, never changed
    KekRewrapService.Result r = new KekRewrapService(tx, store,
        new KekRingRewrapper(new KekRing("k1", Map.of("k1", k1())))).run("k8", 10);
    assertThat(r.failed()).isGreaterThanOrEqualTo(1);
    assertThat(kekOf(a)).isEqualTo("k8");
    ADMIN.sql("DELETE FROM identity.accounts WHERE id = :id").param("id", a).update(); // k8 is nobody's KEK
  }
}
