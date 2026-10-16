package in.dexter.testkit.demo.app;

import in.dexter.cache.TenantCache;
import in.dexter.core.error.NotFoundException;
import in.dexter.core.tenant.Tenant;
import in.dexter.db.page.Page;
import in.dexter.db.page.PageRequest;
import in.dexter.db.tenant.TenantTx;
import in.dexter.testkit.demo.config.CacheScopes;
import in.dexter.testkit.demo.domain.NoteRules;
import in.dexter.testkit.demo.infra.NoteRepository;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Cached reads (app, ADR-0004): validate, then read through {@link TenantCache} <em>outside</em> any
 * transaction; on a miss the loader opens its own read-only {@link TenantTx}. Key parameters are exactly
 * the request parameters, so every page and filter is its own entry under the {@code notes} scope.
 */
public final class NoteQueries {
  private final TenantTx tx;
  private final NoteRepository repo;
  private final TenantCache cache;
  /** Test seam: runs inside every cache load, after the DB read (counts loads, slows or pauses them). */
  private final Runnable afterLoad;

  public NoteQueries(TenantTx tx, NoteRepository repo, TenantCache cache, Runnable afterLoad) {
    this.tx = tx;
    this.repo = repo;
    this.cache = cache;
    this.afterLoad = Objects.requireNonNull(afterLoad, "afterLoad");
  }

  public NoteView get(Tenant t, UUID id) {
    return cache.find(t, CacheScopes.NOTES, Map.of("id", id), NoteView.class,
            () -> tx.read(t, () -> {
              var note = repo.find(t.accountId(), id).map(NoteView::of);
              afterLoad.run();
              return note;
            }))
        .orElseThrow(() -> new NotFoundException("note"));
  }

  public Page<NoteView> list(Tenant t, PageRequest req, String kind) {
    if (kind != null) {
      NoteRules.validate("x", kind, 0);
    }
    Map<String, Object> params = new HashMap<>();
    params.put("list", "created_desc");
    params.put("limit", req.limit());
    params.put("cursor", req.cursor());
    params.put("kind", kind);
    return cache.get(t, CacheScopes.NOTES, params, CachedNotePage.class,
            () -> tx.read(t, () -> {
              CachedNotePage page = CachedNotePage.of(repo.list(req, kind).map(NoteView::of));
              afterLoad.run();
              return page;
            }))
        .toPage();
  }
}
