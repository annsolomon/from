package in.dexter.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.dexter.core.tenant.Tenant;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CacheKeysTest {
  static final UUID A = UUID.fromString("01920000-0000-7000-8000-000000000001");
  static final UUID B = UUID.fromString("01920000-0000-7000-8000-000000000002");
  final CacheKeys keys = new CacheKeys("ledger");
  final CacheScope summaries = CacheScope.tenant("summaries", Duration.ofMinutes(10));
  final CacheScope rules = CacheScope.global("rules", Duration.ofMinutes(15));

  @Test
  void tenantKeysFollowTheAdrSchemeWithAHashTag() {
    assertThat(keys.genKey(summaries, A)).isEqualTo("gen:ledger:{" + A + "}:summaries");
    assertThat(keys.valueKey(summaries, A, 5, "0123456789abcdef"))
        .isEqualTo("ledger:v1:{" + A + "}:summaries:g5:0123456789abcdef");
    assertThat(CacheKeys.lockKey("ledger:v1:x")).isEqualTo("lock:ledger:v1:x");
    assertThat(keys.valuePrefix(summaries.withSchema(3), A)).isEqualTo("ledger:v3:{" + A + "}:summaries");
  }

  @Test
  void globalKeysHaveNoAccount() {
    assertThat(keys.genKey(rules, null)).isEqualTo("gen:ledger:global:rules");
    assertThat(keys.valueKey(rules, null, 0, "h")).isEqualTo("ledger:v1:global:rules:g0:h");
    assertThatThrownBy(() -> keys.genKey(rules, A)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> keys.genKey(summaries, null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  void paramsHashIsOrderIndependentAnd16Hex() {
    Map<String, Object> p1 = new LinkedHashMap<>();
    p1.put("month", "2026-09");
    p1.put("tz", "Asia/Kolkata");
    Map<String, Object> p2 = new LinkedHashMap<>();
    p2.put("tz", "Asia/Kolkata");
    p2.put("month", "2026-09");
    assertThat(CacheKeys.paramsHash(p1)).isEqualTo(CacheKeys.paramsHash(p2)).matches("[0-9a-f]{16}");
    assertThat(CacheKeys.paramsHash(Map.of("month", "2026-10"))).isNotEqualTo(CacheKeys.paramsHash(Map.of("month", "2026-09")));
  }

  @Test
  void canonicalFormEscapesSeparatorsAndKeepsNulls() {
    Map<String, Object> p = new HashMap<>();
    p.put("a", "x&b=1");
    p.put("b", null);
    assertThat(CacheKeys.canonical(p)).isEqualTo("a=x%26b%3D1&b");
    // "a=x&b=1" as two params must not collide with one param whose value contains "&b="
    assertThat(CacheKeys.paramsHash(p)).isNotEqualTo(CacheKeys.paramsHash(Map.of("a", "x", "b", "1")));
  }

  @Test
  void tenantParamsHashIncludesTheRole() {
    Map<String, Object> p = Map.of("id", "n1");
    String user = TenantCache.paramsHash(Tenant.user(A), p);
    String admin = TenantCache.paramsHash(new Tenant(A, Tenant.SUPPORT, null), p);
    assertThat(user).isNotEqualTo(admin);
    assertThatThrownBy(() -> TenantCache.paramsHash(Tenant.user(A), Map.of("@role", "USER")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void accountPatternsCoverEveryPrefixAndOnlyThatAccount() {
    String[] patterns = keys.accountPatterns(A);
    assertThat(patterns).containsExactly("ledger:v*:{" + A + "}:*", "lock:ledger:v*:{" + A + "}:*",
        "gen:ledger:{" + A + "}:*");
    for (String p : patterns) {
      assertThat(p).doesNotContain(B.toString());
    }
  }

  @Test
  void namesAreValidated() {
    assertThatThrownBy(() -> new CacheKeys("Ledger")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CacheKeys("led ger")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> CacheScope.tenant("Summaries", Duration.ofMinutes(1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> CacheScope.tenant("a:b", Duration.ofMinutes(1))).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> CacheScope.tenant("ok", Duration.ofHours(2))).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> CacheScope.tenant("ok", Duration.ofMillis(10))).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> summaries.withSchema(0)).isInstanceOf(IllegalArgumentException.class);
  }
}
