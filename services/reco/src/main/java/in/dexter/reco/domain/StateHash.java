package in.dexter.reco.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * The fingerprint of everything a recommendation was computed from (MASTER_PLAN P14's {@code state_hash}).
 *
 * <p>Equal hash means equal inputs, so a sweeper that recomputes and finds the same hash can clear {@code dirty}
 * without writing a new result or publishing an event — which is what keeps a burst of 50 expense events from
 * producing 50 identical "your recommendation changed" notifications.
 *
 * <p>It is a hash of the <em>inputs</em>, not of the output: hashing the output would hide a change in the engine
 * itself, so the engine's own version is part of the input (bump {@link #ENGINE_VERSION} whenever the arithmetic
 * changes, or stale results will be served as fresh).
 */
public final class StateHash {
  /** Bumped whenever the scoring arithmetic changes, so every stored result is recomputed. */
  public static final int ENGINE_VERSION = 1;

  private StateHash() {}

  /**
   * @param cardVersions the catalog version of each candidate product, so a republished card invalidates the hash
   */
  public static String of(SpendVector spend, Collection<String> heldProductIds, Map<String, Integer> cardVersions,
      int savingsWeightBps, int thresholdBps) {
    StringBuilder sb = new StringBuilder(256);
    sb.append("v").append(ENGINE_VERSION).append('|').append(savingsWeightBps).append('|').append(thresholdBps);
    sb.append("|spend:");
    // sorted, so two equal profiles in different map orders hash the same
    spend.monthlyByRoot().entrySet().stream().sorted(Map.Entry.comparingByKey())
        .forEach(e -> sb.append(e.getKey()).append('=').append(e.getValue()).append(','));
    sb.append("|held:");
    List<String> held = new ArrayList<>(heldProductIds == null ? List.of() : heldProductIds);
    held.sort(null);
    held.forEach(h -> sb.append(h).append(','));
    sb.append("|cards:");
    cardVersions.entrySet().stream().sorted(Map.Entry.comparingByKey())
        .forEach(e -> sb.append(e.getKey()).append('@').append(e.getValue()).append(','));
    return sha256Hex(sb.toString());
  }

  static String sha256Hex(String s) {
    try {
      byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(64);
      for (byte b : d) {
        hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required", e);
    }
  }
}
