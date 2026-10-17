package in.dexter.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.core.tenant.Tenant;
import in.dexter.db.tenant.TenantTx;
import in.dexter.ledger.app.MerchantResolver;
import in.dexter.ledger.support.LabelledMerchants;
import in.dexter.ledger.support.LabelledMerchants.Sample;
import in.dexter.ledger.support.LedgerIT;
import in.dexter.ledger.support.SeedMerchants;
import in.dexter.testkit.containers.TestRedis;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Resolver accuracy (MASTER_PLAN P08 DoD: ≥ 90 % top-1 on 500 labelled raw merchant strings from the seed). Three
 * sets, all deterministic: the seed's own descriptor shapes (UPI, card ×3, net banking, wallet, cash) — asserted
 * ≥ 90 %; the same with one human/bank distortion per name (typo, swap, "store"/"online" suffix, 12-char cut) —
 * asserted ≥ 85 %; the seed's local payees (no merchant) — asserted to stay unmatched ≥ 90 %. The report
 * (per method and stage) is printed and written to {@code target/resolver-accuracy.md} for BUILD_README.
 */
class ResolverAccuracyIT extends LedgerIT {
  static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ResolverAccuracyIT.class);
  static final List<String> LOCAL_PAYEES = List.of("Sri Lakshmi Kirana", "Ganesh Tea Stall", "Ramesh Vegetables",
      "Anand Milk Supply", "Sharma Dairy", "Shree Krishna Sweets", "Raju Auto", "Kumar Tailors", "Mahalakshmi Flowers",
      "Iyer Mess", "Patel Provision Store", "Venkat Laundry", "Murugan Fruits", "Local Vendor", "Priya S", "Arjun M",
      "Neha K", "Rahul V", "Divya R", "Karthik P", "Self transfer");

  @Autowired
  MerchantResolver resolver;
  @Autowired
  TenantTx tx;

  /** After the context (Flyway) is up: a static @BeforeAll can run before the first test class migrates. */
  @org.junit.jupiter.api.BeforeEach
  void merchants() {
    SeedMerchants.ensureLoaded(ADMIN);
  }

  record Score(int ok, int n, Map<String, int[]> byMethod, Map<String, int[]> byStage, List<String> misses) {
    double rate() {
      return n == 0 ? 0 : ok / (double) n;
    }
  }

  Score score(List<Sample> samples) {
    Tenant t = Tenant.user(UUID.randomUUID());
    Map<String, MerchantResolver.AliasMatch> pre = resolver.prefetch(t, samples.stream().map(Sample::raw).toList());
    return tx.read(t, () -> {
      MerchantResolver.Session s = resolver.session(pre);
      int ok = 0;
      Map<String, int[]> byMethod = new TreeMap<>();
      Map<String, int[]> byStage = new TreeMap<>();
      List<String> misses = new java.util.ArrayList<>();
      for (Sample x : samples) {
        MerchantResolver.Resolution r = s.resolve(x.raw());
        UUID expected = x.expectedSlug() == null ? null : SeedMerchants.id(x.expectedSlug());
        boolean good = java.util.Objects.equals(expected, r.merchantId());
        ok += good ? 1 : 0;
        int[] m = byMethod.computeIfAbsent(x.method(), k -> new int[2]);
        m[0] += good ? 1 : 0;
        m[1]++;
        int[] st = byStage.computeIfAbsent(r.method(), k -> new int[2]);
        st[0] += good ? 1 : 0;
        st[1]++;
        if (!good && misses.size() < 15) {
          misses.add(x.raw() + " → " + r.merchant() + " (" + r.method() + "), expected " + x.expectedSlug());
        }
      }
      return new Score(ok, samples.size(), byMethod, byStage, misses);
    });
  }

  @Test
  void resolverAccuracy() throws IOException {
    Score seed = score(LabelledMerchants.samples(500, 42L));
    Score noisy = score(LabelledMerchants.noisySamples(500, 7L));
    List<Sample> locals = new java.util.ArrayList<>();
    for (String p : LOCAL_PAYEES) {
      locals.add(new Sample(p, null, "cash"));
      locals.add(new Sample("UPI/123456789012/" + p.toUpperCase().replaceAll("[^A-Z0-9]", "") + "/x12@ybl", null,
          "upi"));
    }
    Score local = score(locals);

    StringBuilder md = new StringBuilder("# Resolver accuracy (ResolverAccuracyIT)\n\n")
        .append("| Set | Top-1 | Rate |\n|---|---|---|\n");
    md.append(row("seed descriptors (500)", seed)).append(row("distorted names (500)", noisy))
        .append(row("local payees → no merchant (42)", local));
    md.append("\n| Set | Stage | Correct / n |\n|---|---|---|\n");
    seed.byStage().forEach((k, v) -> md.append("| seed | ").append(k).append(" | ").append(v[0]).append(" / ")
        .append(v[1]).append(" |\n"));
    noisy.byStage().forEach((k, v) -> md.append("| distorted | ").append(k).append(" | ").append(v[0]).append(" / ")
        .append(v[1]).append(" |\n"));
    md.append("\n| Set | Descriptor shape | Correct / n |\n|---|---|---|\n");
    seed.byMethod().forEach((k, v) -> md.append("| seed | ").append(k).append(" | ").append(v[0]).append(" / ")
        .append(v[1]).append(" |\n"));
    noisy.byMethod().forEach((k, v) -> md.append("| distorted | ").append(k).append(" | ").append(v[0]).append(" / ")
        .append(v[1]).append(" |\n"));
    md.append("\nMisses (distorted, first 15):\n");
    noisy.misses().forEach(m -> md.append("- ").append(m).append('\n'));
    LOG.info("RESOLVER_ACCURACY\n{}", md);
    Files.createDirectories(Path.of("target"));
    Files.writeString(Path.of("target/resolver-accuracy.md"), md);

    assertThat(seed.rate()).as("seed descriptors top-1").isGreaterThanOrEqualTo(0.90);
    assertThat(noisy.rate()).as("distorted names top-1").isGreaterThanOrEqualTo(0.85);
    assertThat(local.rate()).as("local payees stay unmatched").isGreaterThanOrEqualTo(0.90);
  }

  /** The alias stages are cached in Redis under the service's ACL prefix ("LRU cache of resolved aliases"). */
  @Test
  void resolvedAliasesAreCachedInRedis() {
    Tenant t = Tenant.user(UUID.randomUUID());
    resolver.prefetch(t, List.of("UPI/111122223333/SWIGGY/swiggy01@ybl"));
    assertThat(TestRedis.admin().keys("ledger:*merchant-alias*")).isNotEmpty();
    var again = resolver.prefetch(t, List.of("UPI/999988887777/SWIGGY/swiggy77@okaxis"));
    assertThat(again.values().iterator().next().merchant()).isEqualTo("Swiggy");
  }

  static String row(String name, Score s) {
    return "| " + name + " | " + s.ok() + " / " + s.n() + " | " + String.format(java.util.Locale.ROOT, "%.1f %%",
        s.rate() * 100) + " |\n";
  }
}
