package in.dexter.ledger.app;

import in.dexter.core.error.FieldError;
import in.dexter.core.error.ValidationException;
import in.dexter.core.tenant.Tenant;
import in.dexter.db.tenant.TenantTx;
import in.dexter.ledger.app.MerchantStore.Candidate;
import in.dexter.ledger.app.MerchantStore.Suggestion;
import in.dexter.ledger.domain.MerchantText;
import java.util.List;
import java.util.Map;

/** {@code /v1/merchants}: autocomplete (cached, top 8) and the resolver as an endpoint (MASTER_PLAN P08). */
public final class MerchantService {
  public static final int AUTOCOMPLETE_LIMIT = 8;

  /** Cached autocomplete answer (a record, so the cache can store it as JSON). */
  public record Suggestions(List<Suggestion> data) {}

  /** POST /v1/merchants/resolve body. */
  public record ResolveRequest(String merchant) {}

  public record ResolveResponse(String input, String normalized, MerchantResolver.Resolution resolution,
      List<Candidate> candidates) {}

  private final TenantTx tx;
  private final MerchantStore store;
  private final MerchantResolver resolver;
  private final ReferenceCache cache;

  public MerchantService(TenantTx tx, MerchantStore store, MerchantResolver resolver, ReferenceCache cache) {
    this.tx = tx;
    this.store = store;
    this.resolver = resolver;
    this.cache = cache;
  }

  /** At least 2 characters after normalisation; the same query is answered from Redis for 10 minutes. */
  public Suggestions autocomplete(Tenant tenant, String q) {
    String query = MerchantText.query(q);
    if (query.length() < 2) {
      throw new ValidationException(List.of(new FieldError("q", "TOO_SHORT", "At least 2 letters or digits")));
    }
    return cache.get(ReferenceCache.Scope.MERCHANT_AUTOCOMPLETE, query, Suggestions.class,
        () -> new Suggestions(tx.read(tenant, () -> store.autocomplete(query, AUTOCOMPLETE_LIMIT))));
  }

  public ResolveResponse resolve(Tenant tenant, ResolveRequest req) {
    String raw = req == null || req.merchant() == null ? "" : req.merchant().strip();
    if (raw.isEmpty() || raw.length() > 200) {
      throw new ValidationException(List.of(new FieldError("merchant", "INVALID", "1-200 characters")));
    }
    Map<String, MerchantResolver.AliasMatch> aliases = resolver.prefetch(tenant, List.of(raw));
    return tx.read(tenant, () -> {
      MerchantResolver.Resolution r = resolver.session(aliases).resolve(raw);
      return new ResolveResponse(raw, r.normalized(), r, resolver.candidates(raw));
    });
  }
}
