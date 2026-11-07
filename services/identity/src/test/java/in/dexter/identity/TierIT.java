package in.dexter.identity;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.contracts.events.billing.EntitlementsChangedV1;
import in.dexter.core.id.Ids;
import in.dexter.events.EventEnvelope;
import in.dexter.identity.app.TierService;
import in.dexter.identity.support.IdentityIT;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * P16: the tier billing announces reaches the next token. {@code billing.entitlements.changed} is applied by the
 * billing-tier consumer (called directly here, as planning's ITs call theirs), and a refresh issues a token whose
 * {@code tier} claim is the new one. A replay is a duplicate, an older version is stale, and neither moves the tier.
 */
class TierIT extends IdentityIT {
  @Autowired
  TierService tiers;

  static EventEnvelope event(UUID account, String tier, long version) {
    EntitlementsChangedV1 d = new EntitlementsChangedV1(account, tier, Map.of("adFree", !"FREE".equals(tier)),
        version, Instant.now().toString());
    return new EventEnvelope(Ids.newId(), EntitlementsChangedV1.TYPE, EntitlementsChangedV1.VERSION, "billing",
        Instant.now(), account, account, version, null, JSON.valueToTree(d));
  }

  @Test
  void aNewTierArrivesInTheNextTokenAndOnlyNewerVersionsMoveIt() {
    String email = uniqueEmail("tier");
    JsonNode t = signUpAndLogin(email);
    UUID account = accountIdOf(email);
    assertThat(claims(t.get("accessToken").asString()).get("tier").asString()).isEqualTo("FREE");
    long profileVersion = ADMIN.sql("SELECT version FROM identity.accounts WHERE id = :a").param("a", account)
        .query(Long.class).single();

    EventEnvelope premium = event(account, "PREMIUM", 1);
    assertThat(tiers.onEntitlementsChanged(premium)).isEqualTo(TierService.Outcome.APPLIED);
    assertThat(tiers.onEntitlementsChanged(premium)).as("the same event again").isEqualTo(TierService.Outcome.DUPLICATE);
    Res r = post("/v1/auth/token/refresh", Map.of("refreshToken", t.get("refreshToken").asString()));
    assertThat(r.status()).as(r.toString()).isEqualTo(200);
    assertThat(claims(r.json().get("accessToken").asString()).get("tier").asString()).isEqualTo("PREMIUM");

    // a VIP at version 3, then version 2 (FREE) arriving late through a retry topic: the late one is ignored
    assertThat(tiers.onEntitlementsChanged(event(account, "VIP", 3))).isEqualTo(TierService.Outcome.APPLIED);
    assertThat(tiers.onEntitlementsChanged(event(account, "FREE", 2))).isEqualTo(TierService.Outcome.STALE);
    assertThat(ADMIN.sql("SELECT tier_cache || ':' || tier_version FROM identity.accounts WHERE id = :a")
        .param("a", account).query(String.class).single()).isEqualTo("VIP:3");
    Res r2 = post("/v1/auth/token/refresh", Map.of("refreshToken", r.json().get("refreshToken").asString()));
    assertThat(claims(r2.json().get("accessToken").asString()).get("tier").asString()).isEqualTo("VIP");
    // the profile's ETag version is not a tier's business
    assertThat(ADMIN.sql("SELECT version FROM identity.accounts WHERE id = :a").param("a", account)
        .query(Long.class).single()).isEqualTo(profileVersion);
  }

  @Test
  void otherEventsAndUnknownTiersAreIgnored() {
    UUID account = UUID.randomUUID();
    assertThat(tiers.onEntitlementsChanged(event(account, "GOLD", 1))).isEqualTo(TierService.Outcome.IGNORED);
    EventEnvelope other = new EventEnvelope(Ids.newId(), "billing.subscription.changed", 1, "billing", Instant.now(),
        account, account, 1, null, JSON.createObjectNode());
    assertThat(tiers.onEntitlementsChanged(other)).isEqualTo(TierService.Outcome.IGNORED);
    // an account identity does not know: nothing to update, recorded as stale (the inbox still dedupes it)
    assertThat(tiers.onEntitlementsChanged(event(account, "PREMIUM", 1))).isEqualTo(TierService.Outcome.STALE);
  }
}
