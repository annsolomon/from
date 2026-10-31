package in.dexter.reco.app;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Everything reco reads and writes (MASTER_PLAN P14), as one port per concern. The app layer talks to these; the
 * engine in {@code reco.domain} talks to nothing.
 */
public interface RecoStore {

  /** A month's spend in one category root, the exact figure the windows are rebuilt from. */
  record MonthRow(String categoryRoot, LocalDate month, long totalPaise, int txnCount) {}

  /** One window of the spend profile. */
  record ProfileRow(String categoryRoot, String window, long monthlyAvgPaise, long totalPaise, int txnCount) {}

  /** A card the account holds, projected from portfolio. */
  record HeldCard(UUID userCardId, UUID cardProductId, long projectedVersion) {}

  /** A published card product with its terms, projected from catalog. */
  record CardRow(UUID cardProductId, String slug, String name, String issuerCode, String network, String tier,
      long annualFeePaise, Long feeWaiverSpendPaise, String milestoneJson, String status, int currentVersion,
      long projectedVersion) {}

  /** One reward rule of a card product, projected from the same snapshot. */
  record RuleRow(UUID cardProductId, UUID ruleId, String categoryPath, UUID merchantId, String rewardType,
      int rateBps, long pointValueMilliPaise, Long capPaise, String capPeriod, long minTxnPaise,
      List<String> excludedPaths, int priority) {}

  /** The recompute state of one account. */
  record StateRow(UUID accountId, long ledgerVer, long portfolioVer, long catalogVer, boolean dirty,
      Instant dirtySince, Instant computedAt, int attempts) {}

  /** A stored result. */
  record ResultRow(UUID accountId, String stateHash, Instant computedAt, String payloadJson) {}

  // ---- spend (tenant)

  /** Adds {@code deltaPaise} and {@code deltaCount} to one (root, month), flooring the row at zero. */
  void addSpend(UUID account, String categoryRoot, LocalDate month, long deltaPaise, int deltaCount);

  /** Every month of one root inside {@code from}..{@code to}, for rebuilding that root's three windows. */
  List<MonthRow> monthsOf(UUID account, String categoryRoot, LocalDate from, LocalDate to);

  /** Replaces the three window rows of one root. */
  void putProfile(UUID account, String categoryRoot, List<ProfileRow> windows, long projectedVersion);

  /** Deletes a root's window rows when it no longer has spend in any window. */
  void deleteProfile(UUID account, String categoryRoot);

  /** The whole profile for one window, newest figures first — the scorer's spend vector. */
  List<ProfileRow> profile(UUID account, String window);

  // ---- held cards (tenant)

  boolean upsertHeldCard(UUID account, HeldCard card, long eventVersion);

  boolean deleteHeldCard(UUID account, UUID userCardId, long eventVersion);

  List<HeldCard> heldCards(UUID account);

  // ---- catalog projection (global, written by the PROJECTOR actor)

  boolean upsertCard(CardRow card, List<RuleRow> rules, long eventVersion);

  List<CardRow> publishedCards();

  List<RuleRow> rulesOf(List<UUID> cardProductIds);

  // ---- state and results

  Optional<StateRow> state(UUID account);

  /** Marks the account dirty and raises the given source's high-water mark; creates the row when absent. */
  void markDirty(UUID account, String source, long version, Instant now);

  /** Claims up to {@code batch} accounts dirty since before {@code olderThan}, with SKIP LOCKED. */
  List<UUID> claimDirty(Instant olderThan, int batch);

  /** Records a finished recompute: clears dirty and stamps computed_at. */
  void markComputed(UUID account, Instant now);

  /** Records a failed attempt without clearing dirty, so the sweeper can back off a hot-looping account. */
  void markAttempt(UUID account);

  Optional<ResultRow> result(UUID account);

  /** Stores the latest result and appends it to the history. */
  void putResult(ResultRow row);
}
