package in.dexter.identity.app;

/** Argon2id (MASTER_PLAN 7.1). Implementations bound concurrent hashing (memory-hard: ~19 MiB each). */
public interface PasswordHasher {
  String hash(String raw);

  boolean matches(String raw, String hash);

  /** True when the stored hash uses older parameters (rehash on the next successful login). */
  boolean needsRehash(String hash);

  /** Burns the same time as {@link #matches} for an unknown account, so timing reveals nothing. */
  void dummyMatch(String raw);
}
