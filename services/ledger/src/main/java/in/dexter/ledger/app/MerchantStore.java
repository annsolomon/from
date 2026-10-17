package in.dexter.ledger.app;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** {@code ledger.merchants} + {@code merchant_aliases}: global reference data, read-only for the app (P08). */
public interface MerchantStore {
  /** An alias that matched, with its merchant. */
  record AliasHit(UUID merchantId, String merchant, String category, String alias, int weight) {}

  /** A trigram candidate: the alias' compact form and its similarity to the input. */
  record Candidate(UUID merchantId, String merchant, String category, String aliasCompact, int weight,
      double similarity) {}

  record Suggestion(UUID merchantId, String merchant, String categoryPath) {}

  /** The first of {@code variants} (in order) that is an alias; the heaviest alias wins a tie. */
  Optional<AliasHit> exact(List<String> variants);

  /** Same on the space-free alias form. */
  Optional<AliasHit> exactCompact(List<String> compacts);

  /** A descriptor cut at {@code MerchantText.DESCRIPTOR_TRUNCATION}: the alias that starts with it. */
  Optional<AliasHit> compactPrefix(String compact);

  /** Aliases similar to {@code compact} (pg_trgm {@code %} at {@code threshold}), most similar first. */
  List<Candidate> similar(String compact, double threshold, int limit);

  /** Prefix / word-prefix / substring / trigram matches for the autocomplete box. */
  List<Suggestion> autocomplete(String query, int limit);
}
