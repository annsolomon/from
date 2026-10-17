package in.dexter.identity.app;

import java.time.Instant;
import java.util.List;

/** {@code identity.signing_keys} (global; runs with the SYSTEM tenant). */
public interface SigningKeyStore {

  record KeyRow(String kid, String publicJwk, byte[] privateEnc, String kekId, String status, Instant createdAt,
      Instant activatedAt, Instant retiredAt) {}

  List<KeyRow> all();

  /** Transaction-scoped advisory lock: one instance rotates at a time. */
  boolean tryLock();

  void insertNext(String kid, String publicJwk, byte[] privateEnc, String kekId);

  void activate(String kid, Instant now);

  void retire(String kid, Instant now);

  int deleteRetiredBefore(Instant cutoff);
}
