package in.dexter.billing;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.billing.support.BillingIT;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** GET /v1/plans and /v1/plans/{code}: public, the price before GST, the GST and what a checkout charges. */
class PlansIT extends BillingIT {

  @Test
  void thePriceListIsPublicAndCarriesTheGst() {
    Res r = send("GET", "/v1/plans", null, null);
    assertThat(r.status()).as(r.toString()).isEqualTo(200);
    JsonNode data = r.json().get("data");
    assertThat(data.size()).isEqualTo(3);
    assertThat(data.get(0).get("code").asString()).isEqualTo("FREE");
    JsonNode premium = data.get(1);
    assertThat(premium.get("code").asString()).isEqualTo("PREMIUM");
    assertThat(premium.get("pricePaise").asLong()).isEqualTo(19_900);
    assertThat(premium.get("gstPaise").asLong()).isEqualTo(3_582);
    assertThat(premium.get("totalPaise").asLong()).isEqualTo(23_482);
    assertThat(premium.get("interval").asString()).isEqualTo("month");
    assertThat(premium.get("entitlements").get("walletOptimizer").asBoolean()).isTrue();
    assertThat(data.get(0).get("totalPaise").asLong()).isZero();
    assertThat(r.header("Cache-Control")).contains("max-age=300");
  }

  @Test
  void onePlanByCodeInAnyCase() {
    Res r = send("GET", "/v1/plans/vip", null, null);
    assertThat(r.status()).as(r.toString()).isEqualTo(200);
    assertThat(r.json().get("totalPaise").asLong()).isEqualTo(58_882);
    Res nf = send("GET", "/v1/plans/GOLD", null, null);
    assertThat(nf.status()).isEqualTo(404);
    assertThat(nf.code()).isEqualTo("PLAN_NOT_FOUND");
  }
}
