package in.dexter.seed;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

/**
 * Argon2id hashes in the encoding of spring-security-crypto's {@code Argon2PasswordEncoder}
 * ({@code $argon2id$v=19$m=19456,t=2,p=1$<salt>$<hash>}, unpadded base64), with the MASTER_PLAN 7.1 parameters, so
 * identity (P04) verifies them with its normal encoder.
 *
 * <p>Hashing is deliberately slow (~19 MiB, 2 passes), so the generator hashes each distinct password <b>once</b>:
 * the three demo accounts get their own hash, and every bulk account shares one hash of the bulk password. The salt
 * comes from the seeded stream, which makes the output deterministic (dev data only: a fixed salt is never
 * acceptable for real passwords, and identity always mints random salts).
 */
public final class Passwords {
  public static final int MEMORY_KIB = 19_456;
  public static final int ITERATIONS = 2;
  public static final int PARALLELISM = 1;
  static final int SALT_BYTES = 16;
  static final int HASH_BYTES = 32;

  private Passwords() {}

  public static String argon2id(String password, Rng saltSource) {
    byte[] salt = new byte[SALT_BYTES];
    for (int i = 0; i < SALT_BYTES; i += 8) {
      long v = saltSource.nextLong();
      for (int j = 0; j < 8 && i + j < SALT_BYTES; j++) {
        salt[i + j] = (byte) (v >>> (8 * j));
      }
    }
    Argon2Parameters params = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
        .withVersion(Argon2Parameters.ARGON2_VERSION_13)
        .withMemoryAsKB(MEMORY_KIB)
        .withIterations(ITERATIONS)
        .withParallelism(PARALLELISM)
        .withSalt(salt)
        .build();
    Argon2BytesGenerator gen = new Argon2BytesGenerator();
    gen.init(params);
    byte[] hash = new byte[HASH_BYTES];
    gen.generateBytes(password.getBytes(StandardCharsets.UTF_8), hash);
    Base64.Encoder b64 = Base64.getEncoder().withoutPadding();
    return "$argon2id$v=19$m=" + MEMORY_KIB + ",t=" + ITERATIONS + ",p=" + PARALLELISM + "$"
        + b64.encodeToString(salt) + "$" + b64.encodeToString(hash);
  }
}
