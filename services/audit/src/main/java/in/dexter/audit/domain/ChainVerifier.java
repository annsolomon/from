package in.dexter.audit.domain;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

/**
 * Walks the audit chain in seq order (MASTER_PLAN P18, ADR-0024 §1). Each row must: follow the previous seq by
 * exactly one, carry the previous row's hash as its {@code prev_hash}, and hash to what the database recomputes from
 * its columns. Any anchor at a seq must equal that row's hash (a rewrite of every later row is caught by the anchor
 * exported before it). Fed in batches; the first break stops it.
 */
public final class ChainVerifier {
  /** One row as read back: its stored hashes and the hash recomputed from its columns. */
  public record Row(long seq, byte[] prevHash, byte[] hash, byte[] recomputed) {}

  /** The outcome: {@code ok}, rows checked, and the first broken seq with the reason (when not ok). */
  public record Result(boolean ok, long checked, long headSeq, String headHash, Long brokenAt, String reason,
      int anchorsChecked) {}

  private final Map<Long, byte[]> anchors;
  private long expectedSeq;
  private byte[] expectedPrev;
  private long checked;
  private int anchorsChecked;
  private Long brokenAt;
  private String reason;

  /**
   * @param genesis the chain's seq-0 hash
   * @param anchors anchored hash by seq (seq 0 = genesis is allowed)
   */
  public ChainVerifier(byte[] genesis, Map<Long, byte[]> anchors) {
    this.expectedSeq = 1;
    this.expectedPrev = genesis.clone();
    this.anchors = Map.copyOf(anchors);
  }

  /** False once broken: stop feeding. */
  public boolean accept(Row r) {
    if (brokenAt != null) {
      return false;
    }
    if (r.seq() != expectedSeq) {
      return fail(expectedSeq, "seq " + expectedSeq + " is missing (next row is " + r.seq() + ")");
    }
    if (!Arrays.equals(r.prevHash(), expectedPrev)) {
      return fail(r.seq(), "prev_hash does not match the previous row's hash");
    }
    if (!Arrays.equals(r.hash(), r.recomputed())) {
      return fail(r.seq(), "the row's content does not match its hash");
    }
    byte[] anchor = anchors.get(r.seq());
    if (anchor != null) {
      anchorsChecked++;
      if (!Arrays.equals(anchor, r.hash())) {
        return fail(r.seq(), "the row's hash does not match the anchor exported for this seq");
      }
    }
    checked++;
    expectedSeq++;
    expectedPrev = r.hash().clone();
    return true;
  }

  private boolean fail(long seq, String why) {
    brokenAt = seq;
    reason = why;
    return false;
  }

  /** The seq the next row must have. */
  public long nextSeq() {
    return expectedSeq;
  }

  /**
   * The result against the stored chain head: a head ahead of the last row means rows were removed from the end.
   */
  public Result finish(long headSeq, byte[] headHash) {
    if (brokenAt == null) {
      long last = expectedSeq - 1;
      if (headSeq != last) {
        fail(last + 1, "the chain head is at seq " + headSeq + " but the log ends at " + last);
      } else if (!Arrays.equals(headHash, expectedPrev)) {
        fail(last, "the chain head's hash does not match the last row");
      }
    }
    return new Result(brokenAt == null, checked, headSeq, HexFormat.of().formatHex(headHash), brokenAt, reason,
        anchorsChecked);
  }

  public Optional<Long> brokenAt() {
    return Optional.ofNullable(brokenAt);
  }
}
