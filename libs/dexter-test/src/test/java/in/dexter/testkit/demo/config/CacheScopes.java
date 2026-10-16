package in.dexter.testkit.demo.config;

import in.dexter.cache.CacheScope;
import java.time.Duration;

/**
 * The demo's cache scopes (ADR-0004 §2): one per invalidation unit, with its TTL (the hard staleness bound).
 * Writes that change notes bump {@link #NOTES} after commit (Path A, {@code NoteService}); the
 * {@code demo.note.*} events bump it again (Path B, {@code NoteCacheInvalidation}).
 */
public final class CacheScopes {
  /** Note detail (+ NOT_FOUND) and every list page of an account. */
  public static final CacheScope NOTES = CacheScope.tenant("notes", Duration.ofMinutes(10));

  private CacheScopes() {}
}
