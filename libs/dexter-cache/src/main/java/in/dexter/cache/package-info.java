/**
 * Redis cache-aside with generation-based invalidation (ADR-0004).
 *
 * <ul>
 *   <li>{@link in.dexter.cache.TenantCache} / {@link in.dexter.cache.GlobalCache}: read-through, one script
 *       round trip (generation + value), single flight (in-JVM future + Redis {@code SET NX PX} lock), TTL with
 *       ±10% jitter, probabilistic early refresh (XFetch), negative caching, bypass on any Redis error;</li>
 *   <li>{@link in.dexter.cache.CacheInvalidator}: Path A (after commit), Path B bumps, privacy purge;</li>
 *   <li>{@link in.dexter.cache.InvalidationConsumer}: base for outbox-driven (Path B) consumers.</li>
 * </ul>
 * Redis is disposable: Postgres stays the source of truth and every read has a DB fallback.
 */
@NullMarked
package in.dexter.cache;

import org.jspecify.annotations.NullMarked;
