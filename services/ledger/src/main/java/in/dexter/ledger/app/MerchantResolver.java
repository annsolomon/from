package in.dexter.ledger.app;

import in.dexter.ledger.app.MerchantStore.AliasHit;
import in.dexter.ledger.app.MerchantStore.Candidate;
import in.dexter.ledger.app.RuleStore.Rule;
import in.dexter.ledger.domain.CategoryPaths;
import in.dexter.ledger.domain.EditDistance;
import in.dexter.ledger.domain.MerchantText;
import in.dexter.ledger.domain.MerchantText.Normalized;
import in.dexter.core.tenant.Tenant;
import in.dexter.db.tenant.TenantTx;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Raw merchant text → merchant + category + confidence 0–1000 (MASTER_PLAN P08). Pipeline:
 * <ol>
 *   <li>normalise ({@link MerchantText}: UPI/NB/POS/wallet shapes, VPA handles, reference numbers, city and company
 *       suffixes);</li>
 *   <li>exact alias on the text variants, then on their space-free forms, then the prefix of a truncated
 *       descriptor;</li>
 *   <li>trigram similarity on the compact form ({@code %}, accepted at {@value #TRIGRAM_THRESHOLD}, most similar
 *       first), and for short names under that threshold one/two-letter typos among the same candidates
 *       ({@link EditDistance});</li>
 *   <li>the user's categorisation rules (priority order) decide the category when one matches;</li>
 *   <li>otherwise the merchant's default category, or {@code general} with confidence 0.</li>
 * </ol>
 * The alias stages are global and cached ({@link ReferenceCache}); rules are per account and applied afterwards.
 */
public final class MerchantResolver {
  /** MASTER_PLAN P08: {@code set_limit(0.45)}. */
  public static final double TRIGRAM_THRESHOLD = 0.45;
  /** Candidates for the typo stage come from a looser trigram match. */
  static final double CANDIDATE_THRESHOLD = 0.2;
  static final int CANDIDATES = 20;

  /** What the (global, cached) alias stages decided for one normalised input. {@code merchantId} null = none. */
  public record AliasMatch(UUID merchantId, String merchant, String category, int confidence, String method) {}

  /** The outcome for one input. {@code merchantId} null = unknown merchant. */
  public record Resolution(String input, String normalized, UUID merchantId, String merchant, String categoryPath,
      String categorySource, int confidence, String method, UUID ruleId) {}

  private final TenantTx tx;
  private final MerchantStore store;
  private final RuleStore rules;
  private final ReferenceCache cache;

  public MerchantResolver(TenantTx tx, MerchantStore store, RuleStore rules, ReferenceCache cache) {
    this.tx = tx;
    this.store = store;
    this.rules = rules;
    this.cache = cache;
  }

  /**
   * The alias stages for many inputs, through the Redis cache. Call it <em>before</em> the write transaction
   * (dexter-cache refuses to run inside one: a loader opens its own read transaction, so nothing uncommitted is ever
   * cached); hand the result to {@link #session(Map)}.
   */
  public Map<String, AliasMatch> prefetch(Tenant tenant, Collection<String> raws) {
    Map<String, AliasMatch> out = new HashMap<>();
    for (String raw : raws) {
      if (raw == null) {
        continue;
      }
      Normalized n = MerchantText.normalize(raw);
      if (n.isEmpty()) {
        continue;
      }
      out.computeIfAbsent(key(n), k -> cache.get(ReferenceCache.Scope.MERCHANT_ALIAS, k, AliasMatch.class,
          () -> tx.read(tenant, () -> match(n))));
    }
    return out;
  }

  /** One unit of work (a request, a bulk call, an import) inside a TenantTx: rules loaded once, aliases memoised. */
  public Session session(Map<String, AliasMatch> prefetched) {
    return new Session(prefetched);
  }

  /** A session without prefetched aliases (every alias is matched in the caller's transaction, uncached). */
  public Session session() {
    return new Session(Map.of());
  }

  /** The trigram candidates for an input (POST /v1/merchants/resolve shows them). */
  public List<Candidate> candidates(String raw) {
    Normalized n = MerchantText.normalize(raw);
    return n.isEmpty() ? List.of() : store.similar(n.compacts().getLast(), CANDIDATE_THRESHOLD, 5);
  }

  public final class Session {
    private List<Rule> accountRules;
    private final Map<String, AliasMatch> memo;

    private Session(Map<String, AliasMatch> prefetched) {
      this.memo = new HashMap<>(prefetched);
    }

    public Resolution resolve(String raw) {
      Normalized n = MerchantText.normalize(raw);
      AliasMatch m = n.isEmpty() ? none() : memo.computeIfAbsent(key(n), k -> match(n));
      Rule rule = rule(n, m);
      if (rule != null) {
        return new Resolution(raw, n.text(), m.merchantId(), m.merchant(), rule.categoryPath(), "rule", 1000,
            m.method(), rule.id());
      }
      if (m.merchantId() != null) {
        return new Resolution(raw, n.text(), m.merchantId(), m.merchant(), m.category(), "resolver", m.confidence(),
            m.method(), null);
      }
      return new Resolution(raw, n.text(), null, null, CategoryPaths.DEFAULT, "resolver", 0, "none", null);
    }

    private Rule rule(Normalized n, AliasMatch m) {
      if (accountRules == null) {
        accountRules = rules.list();
      }
      for (Rule r : accountRules) {
        boolean hit = switch (r.matchType()) {
          case "contains" -> !n.isEmpty() && n.variants().getFirst().contains(r.pattern());
          case "merchant_id" -> m.merchantId() != null && m.merchantId().toString().equals(r.pattern());
          default -> false;
        };
        if (hit) {
          return r;
        }
      }
      return null;
    }
  }

  static String key(Normalized n) {
    return String.join("|", n.variants()) + "#" + String.join("|", n.compacts()) + (n.maybeTruncated() ? "#t" : "");
  }

  static AliasMatch none() {
    return new AliasMatch(null, null, null, 0, "none");
  }

  /** The alias stages for one normalised input (no cache). */
  AliasMatch match(Normalized n) {
    Optional<AliasHit> hit = store.exact(n.variants());
    if (hit.isPresent()) {
      return of(hit.get(), Math.min(1000, 900 + hit.get().weight() / 10), "exact");
    }
    hit = store.exactCompact(n.compacts());
    if (hit.isPresent()) {
      return of(hit.get(), Math.min(980, 880 + hit.get().weight() / 10), "compact");
    }
    if (n.maybeTruncated()) {
      hit = store.compactPrefix(n.compact());
      if (hit.isPresent()) {
        return of(hit.get(), 800, "prefix");
      }
    }
    String compact = n.compacts().getLast();
    if (compact.length() < 3) {
      return none();
    }
    List<Candidate> cands = store.similar(compact, CANDIDATE_THRESHOLD, CANDIDATES);
    if (cands.isEmpty()) {
      return none();
    }
    Candidate top = cands.getFirst();
    if (top.similarity() >= TRIGRAM_THRESHOLD) {
      int conf = 450 + (int) Math.round((top.similarity() - TRIGRAM_THRESHOLD) / (1 - TRIGRAM_THRESHOLD) * 400);
      return new AliasMatch(top.merchantId(), top.merchant(), top.category(), Math.min(850, conf), "trigram");
    }
    int allowed = EditDistance.allowed(compact);
    record Scored(Candidate c, int distance) {}
    Optional<Scored> best = cands.stream()
        .map(c -> new Scored(c, EditDistance.osa(compact, c.aliasCompact())))
        .filter(s -> s.distance() <= allowed)
        .min(Comparator.comparingInt(Scored::distance).thenComparing(s -> -s.c().similarity())
            .thenComparing(s -> -s.c().weight()));
    if (best.isPresent()) {
      Candidate c = best.get().c();
      return new AliasMatch(c.merchantId(), c.merchant(), c.category(), best.get().distance() == 1 ? 600 : 500,
          "fuzzy");
    }
    return none();
  }

  private static AliasMatch of(AliasHit h, int confidence, String method) {
    return new AliasMatch(h.merchantId(), h.merchant(), h.category(), confidence, method);
  }
}
