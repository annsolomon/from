package in.dexter.billing;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.billing.support.BillingIT;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** GET /v1/invoices and /v1/invoices/{id}: one invoice per succeeded payment, with the mock GST split as its lines. */
class InvoicesIT extends BillingIT {

  @Test
  void insideTheSupplierStateTheInvoiceSplitsCgstAndSgst() {
    UUID a = UUID.randomUUID();
    Res c = subscribe(a);
    UUID invoiceId = UUID.fromString(c.json().get("invoiceId").asString());
    Res r = get("/v1/invoices/" + invoiceId, a);
    assertThat(r.status()).as(r.toString()).isEqualTo(200);
    JsonNode i = r.json();
    assertThat(i.get("number").asString()).matches("DX/\\d{4}-\\d{2}/\\d{6,}");
    assertThat(i.get("taxRegime").asString()).isEqualTo("intra");
    assertThat(i.get("supplierState").asString()).isEqualTo("KA");
    assertThat(i.get("placeOfSupply").asString()).isEqualTo("KA");
    assertThat(i.get("subtotalPaise").asLong()).isEqualTo(19_900);
    assertThat(i.get("taxPaise").asLong()).isEqualTo(3_582);
    assertThat(i.get("totalPaise").asLong()).isEqualTo(23_482)
        .isEqualTo(c.json().get("payment").get("amountPaise").asLong());
    JsonNode lines = i.get("lines");
    assertThat(lines.size()).isEqualTo(3);
    assertThat(lines.get(1).get("kind").asString()).isEqualTo("cgst");
    assertThat(lines.get(2).get("kind").asString()).isEqualTo("sgst");
    long sum = 0;
    for (JsonNode l : lines) {
      sum += l.get("amountPaise").asLong();
    }
    assertThat(sum).as("the lines add up to the total").isEqualTo(23_482);
    assertThat(i.get("paymentId").asString()).isEqualTo(c.json().get("payment").get("id").asString());
  }

  @Test
  void elsewhereItIsIgstAndTheListIsNewestFirstWithItsLines() {
    UUID a = UUID.randomUUID();
    subscribe(a);
    assertThat(cancel(a).status()).isEqualTo(200);
    ADMIN.sql("UPDATE billing.subscriptions SET status = 'canceled', ended_at = now() WHERE account_id = :a")
        .param("a", a).update();
    Res second = checkout(a, UUID.randomUUID(), "VIP", "pm_mock_success", "DL");
    assertThat(second.status()).as(second.toString()).isEqualTo(201);
    Res list = get("/v1/invoices", a);
    assertThat(list.status()).isEqualTo(200);
    JsonNode data = list.json().get("data");
    assertThat(data.size()).isEqualTo(2);
    assertThat(data.get(0).get("taxRegime").asString()).as("newest first: the VIP one").isEqualTo("inter");
    assertThat(data.get(0).get("lines").get(1).get("kind").asString()).isEqualTo("igst");
    assertThat(data.get(0).get("lines").get(1).get("rateBps").asInt()).isEqualTo(1800);
    assertThat(data.get(1).get("taxRegime").asString()).isEqualTo("intra");
    assertThat(list.json().get("page").get("hasMore").asBoolean()).isFalse();
  }

  @Test
  void anotherAccountsInvoiceIsNotFound() {
    UUID a = UUID.randomUUID();
    UUID invoiceId = UUID.fromString(subscribe(a).json().get("invoiceId").asString());
    Res r = get("/v1/invoices/" + invoiceId, UUID.randomUUID());
    assertThat(r.status()).isEqualTo(404);
    assertThat(get("/v1/invoices", UUID.randomUUID()).json().get("data").size()).isZero();
  }
}
