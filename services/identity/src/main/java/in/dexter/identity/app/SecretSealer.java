package in.dexter.identity.app;

/** Wraps small secrets with the KEK (admin TOTP secrets: admins have no per-account DEK). */
public interface SecretSealer {
  record Sealed(String kekId, byte[] bytes) {}

  Sealed seal(String aad, byte[] plaintext);

  byte[] open(String kekId, String aad, byte[] sealed);
}
