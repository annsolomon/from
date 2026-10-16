package in.dexter.seed;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

/** The precomputed hashes verify with the encoder identity (P04) uses, with the MASTER_PLAN 7.1 parameters. */
class PasswordsTest {

  @Test
  void springArgon2EncoderAcceptsTheSeedHashes() {
    String hash = Passwords.argon2id("Asha-Demo-2026!", new Rng(1).fork("salt"));
    assertThat(hash).startsWith("$argon2id$v=19$m=19456,t=2,p=1$");
    Argon2PasswordEncoder encoder = new Argon2PasswordEncoder(16, 32, 1, 19_456, 2);
    assertThat(encoder.matches("Asha-Demo-2026!", hash)).isTrue();
    assertThat(encoder.matches("asha-demo-2026!", hash)).isFalse();
    // the parameters travel inside the hash, so the default encoder verifies too
    assertThat(Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8().matches("Asha-Demo-2026!", hash)).isTrue();
  }

  @Test
  void theSameSaltStreamGivesTheSameHash() {
    assertThat(Passwords.argon2id("x", new Rng(5))).isEqualTo(Passwords.argon2id("x", new Rng(5)))
        .isNotEqualTo(Passwords.argon2id("x", new Rng(6)));
  }
}
