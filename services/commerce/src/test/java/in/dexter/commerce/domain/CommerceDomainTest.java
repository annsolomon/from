package in.dexter.commerce.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Weighted rotation, the status machines, postback signatures, visitor hashes. */
class CommerceDomainTest {
  static final UUID C1 = UUID.fromString("0192f1e2-0000-7000-8000-0000000c0001");
  static final UUID C2 = UUID.fromString("0192f1e2-0000-7000-8000-0000000c0002");
  static final UUID K1 = UUID.fromString("0192f1e2-0000-7000-8000-0000000c1001");
  static final UUID K2 = UUID.fromString("0192f1e2-0000-7000-8000-0000000c1002");
  static final UUID K3 = UUID.fromString("0192f1e2-0000-7000-8000-0000000c1003");

  @Test
  void weightedRotationIsProportionalToTheWeights() {
    List<AdRotation.Option> options = List.of(new AdRotation.Option(C1, K1, 6), new AdRotation.Option(C1, K2, 3),
        new AdRotation.Option(C2, K3, 1));
    // every roll maps to exactly one option, in weight-sized bands
    Map<UUID, Integer> exact = new HashMap<>();
    for (long roll = 0; roll < 10; roll++) {
      exact.merge(AdRotation.pick(options, roll).creativeId(), 1, Integer::sum);
    }
    assertThat(exact).containsEntry(K1, 6).containsEntry(K2, 3).containsEntry(K3, 1);
    // and a uniform draw follows them (seeded: deterministic)
    SplittableRandom random = new SplittableRandom(17);
    Map<UUID, Integer> drawn = new HashMap<>();
    int n = 100_000;
    for (int i = 0; i < n; i++) {
      drawn.merge(AdRotation.pick(options, random.nextLong(AdRotation.totalWeight(options))).creativeId(), 1,
          Integer::sum);
    }
    assertThat(drawn.get(K1)).isBetween(59_000, 61_000);
    assertThat(drawn.get(K2)).isBetween(29_000, 31_000);
    assertThat(drawn.get(K3)).isBetween(9_400, 10_600);
    assertThatThrownBy(() -> AdRotation.pick(options, 10)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void applicationAndConversionStatusMachines() {
    assertThat(ApplicationStatus.SUBMITTED.operatorMayMoveTo(ApplicationStatus.IN_REVIEW)).isTrue();
    assertThat(ApplicationStatus.SUBMITTED.operatorMayMoveTo(ApplicationStatus.APPROVED)).isTrue();
    assertThat(ApplicationStatus.IN_REVIEW.operatorMayMoveTo(ApplicationStatus.IN_REVIEW)).isFalse();
    assertThat(ApplicationStatus.REJECTED.operatorMayMoveTo(ApplicationStatus.APPROVED)).isFalse();
    assertThat(ApplicationStatus.WITHDRAWN.isOpen()).isFalse();
    assertThat(ApplicationStatus.ofDb("in_review")).isEqualTo(ApplicationStatus.IN_REVIEW);
    assertThat(ConversionStatus.PENDING.mayMoveTo(ConversionStatus.APPROVED)).isTrue();
    assertThat(ConversionStatus.PENDING.mayMoveTo(ConversionStatus.PENDING)).isFalse();
    assertThat(ConversionStatus.APPROVED.mayMoveTo(ConversionStatus.REJECTED)).isFalse();
  }

  @Test
  void postbackSignatures() {
    byte[] secret = "postback-secret-0123456789".getBytes(StandardCharsets.UTF_8);
    byte[] body = "{\"ref\":\"o-1\"}".getBytes(StandardCharsets.UTF_8);
    Instant now = Instant.parse("2026-11-08T10:00:00Z");
    String h = PostbackSignature.header(secret, now.getEpochSecond(), body);
    assertThat(PostbackSignature.verify(secret, h, body, now, Duration.ofMinutes(5)))
        .isEqualTo(PostbackSignature.Check.OK);
    assertThat(PostbackSignature.verify(secret, h, "{}".getBytes(), now, Duration.ofMinutes(5)))
        .isEqualTo(PostbackSignature.Check.MISMATCH);
    assertThat(PostbackSignature.verify(secret, h, body, now.plusSeconds(301), Duration.ofMinutes(5)))
        .isEqualTo(PostbackSignature.Check.STALE);
    assertThat(PostbackSignature.verify(secret, null, body, now, Duration.ofMinutes(5)))
        .isEqualTo(PostbackSignature.Check.MISSING);
  }

  @Test
  void visitorHashesAreTheAccountOrTheSaltedAddress() {
    byte[] ip = Hashing.salted("salt-0123456789ab", "ip:10.0.0.1");
    byte[] ua = Hashing.salted("salt-0123456789ab", "ua:x");
    UUID a = UUID.fromString("0192f1e2-0000-7000-8000-0000000a0001");
    assertThat(Hashing.visitor("salt-0123456789ab", a, ip, ua))
        .isEqualTo(Hashing.visitor("salt-0123456789ab", a, new byte[32], new byte[32]));
    assertThat(Hashing.visitor("salt-0123456789ab", null, ip, ua))
        .isNotEqualTo(Hashing.visitor("salt-0123456789ab", null, ip, Hashing.salted("salt-0123456789ab", "ua:y")));
    assertThat(Hashing.visitor("salt-0123456789ab", null, ip, ua)).hasSize(32);
  }
}
