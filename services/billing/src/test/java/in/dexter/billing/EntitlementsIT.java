package in.dexter.billing;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.billing.app.EntitlementService;
import in.dexter.billing.support.BillingIT;
import in.dexter.cache.CacheInvalidator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * GET /v1/entitlements/me (MASTER_PLAN P16): FREE until a subscription entitles, the plan's map with unexpired
 * overrides laid over it, cached 60 s per account and dropped after every change that commits.
 */
class EntitlementsIT extends BillingIT {
  @Autowired
  CacheInvalidator invalidator;

  static void override(UUID account, String key, String valueJson, String expiresIn) {
    ADMIN.sql("INSERT INTO billing.entitlement_overrides (account_id, key, value, reason, expires_at)"
            + " VALUES (:a, :k, CAST(:v AS jsonb), 'test', now() + CAST(:e AS interval))")
        .param("a", account).param("k", key).param("v", valueJson).param("e", expiresIn).update();
  }

  @Test
  void anAccountWithNoSubscriptionIsFree() {
    Res r = get("/v1/entitlements/me", UUID.randomUUID());
    assertThat(r.status()).as(r.toString()).isEqualTo(200);
    JsonNode b = r.json();
    assertThat(b.get("tier").asString()).isEqualTo("FREE");
    assertThat(b.get("planCode").asString()).isEqualTo("FREE");
    assertThat(b.get("entitlements").get("cardsMax").asInt()).isEqualTo(3);
    assertThat(b.get("entitlements").get("walletOptimizer").asBoolean()).isFalse();
    assertThat(b.get("version").asLong()).isZero();
    assertThat(r.header("Cache-Control")).contains("private").contains("max-age=60");
  }

  @Test
  void unexpiredOverridesLayOverThePlanAndExpiredOnesDoNot() {
    UUID a = UUID.randomUUID();
    override(a, "adFree", "true", "7 days");
    override(a, "cardsMax", "25", "-1 hour");
    JsonNode b = get("/v1/entitlements/me", a).json();
    assertThat(b.get("tier").asString()).isEqualTo("FREE");
    assertThat(b.get("entitlements").get("adFree").asBoolean()).as("an ad holiday").isTrue();
    assertThat(b.get("entitlements").get("cardsMax").asInt()).as("the lapsed override").isEqualTo(3);
    assertThat(b.get("overrides").size()).isEqualTo(1);
    assertThat(b.get("overrides").get(0).get("key").asString()).isEqualTo("adFree");
  }

  @Test
  void theReadIsCachedAndACommittedChangeDropsTheEntry() {
    UUID a = UUID.randomUUID();
    assertThat(get("/v1/entitlements/me", a).json().get("entitlements").get("adFree").asBoolean()).isFalse();
    // written behind the service's back: the cached answer stands (that is what the cache is for)
    override(a, "adFree", "true", "1 day");
    assertThat(get("/v1/entitlements/me", a).json().get("entitlements").get("adFree").asBoolean())
        .as("served from the cache").isFalse();
    invalidator.bump(a, List.of(EntitlementService.SCOPE));
    assertThat(get("/v1/entitlements/me", a).json().get("entitlements").get("adFree").asBoolean())
        .as("after a bump the database answers").isTrue();
    // a subscription starting is a change billing makes itself: the entry is dropped after its commit
    assertThat(get("/v1/entitlements/me", a).json().get("tier").asString()).isEqualTo("FREE");
    subscribe(a);
    JsonNode after = get("/v1/entitlements/me", a).json();
    assertThat(after.get("tier").asString()).isEqualTo("PREMIUM");
    assertThat(after.get("version").asLong()).isEqualTo(1);
    assertThat(after.get("subscriptionStatus").asString()).isEqualTo("active");
  }
}
