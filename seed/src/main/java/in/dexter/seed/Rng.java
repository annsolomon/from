package in.dexter.seed;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * SplitMix64. Specified here rather than taken from the JDK so the output never depends on the JVM: same seed, same
 * stream, everywhere. Every logical stream is a {@link #fork(String)} of its parent, keyed by a stable label (an
 * email, an account index), so adding a stream never shifts the others and a demo account looks the same in every
 * size.
 *
 * <p>Floating point only feeds distributions; anything stored (paise, dates) is rounded from it. All transcendental
 * functions go through {@link StrictMath}, which is bit-identical across platforms.
 */
public final class Rng {
  private final long seed;
  private long state;

  public Rng(long seed) {
    this.seed = seed;
    this.state = seed;
  }

  public long nextLong() {
    long z = (state += 0x9E3779B97F4A7C15L);
    z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
    z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
    return z ^ (z >>> 31);
  }

  /** Uniform in [0, bound). Lemire's multiply-shift with rejection, so unbiased. */
  public int nextInt(int bound) {
    if (bound <= 0) {
      throw new IllegalArgumentException("bound must be positive: " + bound);
    }
    long m = (nextLong() >>> 32) * bound;
    long low = m & 0xFFFFFFFFL;
    if (low < bound) {
      long threshold = (0x1_0000_0000L - bound) % bound;
      while (low < threshold) {
        m = (nextLong() >>> 32) * bound;
        low = m & 0xFFFFFFFFL;
      }
    }
    return (int) (m >>> 32);
  }

  /** Uniform in [lo, hi]. */
  public int between(int lo, int hi) {
    return lo + nextInt(hi - lo + 1);
  }

  /** Uniform in [0, 1) with 53 bits. */
  public double unit() {
    return (nextLong() >>> 11) * 0x1.0p-53;
  }

  /** True with probability bps / 10,000. */
  public boolean chance(int bps) {
    return nextInt(10_000) < bps;
  }

  /** Standard normal (Box-Muller, one value per call). */
  public double gaussian() {
    double u1 = 1.0 - unit(); // (0, 1]
    double u2 = unit();
    return StrictMath.sqrt(-2.0 * StrictMath.log(u1)) * StrictMath.cos(2.0 * StrictMath.PI * u2);
  }

  /** Log-normal with the given median and sigma: median * e^(sigma * N(0,1)). */
  public double logNormal(double median, double sigma) {
    return median * StrictMath.exp(sigma * gaussian());
  }

  public <T> T pick(List<T> items) {
    return items.get(nextInt(items.size()));
  }

  /** Index drawn proportionally to integer weights, given as a cumulative (strictly increasing at picks) array. */
  public int weighted(long[] cumulative) {
    long total = cumulative[cumulative.length - 1];
    long r = Math.floorMod(nextLong(), total);
    int lo = 0;
    int hi = cumulative.length - 1;
    while (lo < hi) {
      int mid = (lo + hi) >>> 1;
      if (cumulative[mid] > r) {
        hi = mid;
      } else {
        lo = mid + 1;
      }
    }
    return lo;
  }

  /** An independent stream for {@code label}: depends on this stream's seed and the label, never on call order. */
  public Rng fork(String label) {
    return new Rng(mix(seed ^ mix(fnv1a64(label))));
  }

  static long mix(long z) {
    z = (z ^ (z >>> 33)) * 0xFF51AFD7ED558CCDL;
    z = (z ^ (z >>> 33)) * 0xC4CEB9FE1A85EC53L;
    return z ^ (z >>> 33);
  }

  static long fnv1a64(String s) {
    long h = 0xCBF29CE484222325L;
    for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
      h ^= (b & 0xFF);
      h *= 0x100000001B3L;
    }
    return h;
  }
}
