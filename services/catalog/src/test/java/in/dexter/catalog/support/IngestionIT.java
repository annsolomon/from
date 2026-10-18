package in.dexter.catalog.support;

import in.dexter.catalog.config.IngestionWorker;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * Ingestion ITs (MASTER_PLAN P10): issuer pages on the local {@link MockIssuerSite}, sources and runs through the
 * admin API, the collector driven synchronously ({@link IngestionWorker#drain()}; the background worker is off).
 */
public abstract class IngestionIT extends CatalogIT {
  @Autowired
  protected IngestionWorker worker;

  /**
   * An issuer product page in the wording the extractor reads; it states everything {@link Cards#doc} publishes
   * (fees, ₹1 lakh waiver, 3.5 % forex, 3.5 % a month, 1 % fuel, delivery and base cashback).
   */
  public static String page(String name, long joiningRupees, long annualRupees, int deliveryPct, String extra) {
    return "<!doctype html><html><head><title>" + name + " | Test Bank</title></head><body><h1>" + name + "</h1>"
        + "<ul><li>Joining fee: ₹" + joiningRupees + " + GST</li><li>Annual fee: ₹" + annualRupees + " + GST</li>"
        + "<li>Annual fee waived on spends of ₹1 lakh</li><li>Forex markup: 3.5%</li>"
        + "<li>Interest 3.5% per month</li><li>1% fuel surcharge waiver</li></ul>"
        + "<p>" + deliveryPct + "% cashback on food delivery. 1% cashback on all other spends.</p>"
        + "<footer>" + (extra == null ? "" : extra) + "</footer></body></html>";
  }

  /** A published card (issuer {@code issuer}, fees ₹500); returns its id. */
  public UUID card(String issuer, String slug, String name) {
    UUID id = newCard(Cards.doc(slug, name, issuer, "visa", 50_000));
    Res p = publish(id);
    if (p.status() != 200) {
      throw new AssertionError(p.toString());
    }
    return id;
  }

  public Res createSource(String issuer, UUID cardId, String url, String kind) {
    Map<String, Object> b = new LinkedHashMap<>();
    b.put("issuerCode", issuer);
    b.put("cardProductId", cardId);
    b.put("url", url);
    b.put("kind", kind);
    return as(editor(), "POST", "/v1/admin/ingestion/sources", b);
  }

  public UUID source(String issuer, UUID cardId, String url, String kind) {
    Res r = createSource(issuer, cardId, url, kind);
    if (r.status() != 201) {
      throw new AssertionError(r.toString());
    }
    return UUID.fromString(r.json().get("id").asString());
  }

  /** Queues a run of the sources and executes it now; returns the finished run. */
  public JsonNode crawl(UUID... sources) {
    Res r = as(editor(), "POST", "/v1/admin/ingestion/runs", Map.of("sourceIds", List.of(sources)));
    if (r.status() != 202) {
      throw new AssertionError(r.toString());
    }
    String id = r.json().get("id").asString();
    worker.drain();
    return as(editor(), "GET", "/v1/admin/ingestion/runs/" + id, null).json();
  }

  public static long stat(JsonNode run, String name) {
    JsonNode v = run.get("checkpoint").get("stats").get(name);
    return v == null ? 0 : v.asLong();
  }

  /** The card's review items of one status (oldest first). */
  public List<JsonNode> reviews(UUID cardId, String status) {
    List<JsonNode> out = new java.util.ArrayList<>();
    String cursor = null;
    do {
      Res r = as(editor(), "GET", "/v1/admin/review?status=" + status + "&limit=100"
          + (cursor == null ? "" : "&cursor=" + java.net.URLEncoder.encode(cursor, java.nio.charset.StandardCharsets.UTF_8)),
          null);
      if (r.status() != 200) {
        throw new AssertionError(r.toString());
      }
      r.json().get("data").forEach(i -> {
        if (i.get("cardProductId").asString().equals(cardId.toString())) {
          out.add(i);
        }
      });
      JsonNode next = r.json().get("page").get("nextCursor");
      cursor = next == null || next.isNull() ? null : next.asString();
    } while (cursor != null);
    return out;
  }

  public Res approve(String itemId) {
    return as(editor(), "POST", "/v1/admin/review/" + itemId + ":approve", null);
  }

  public long versions(UUID cardId) {
    return ADMIN.sql("SELECT count(1) FROM catalog.card_product_versions WHERE card_product_id = :c")
        .param("c", cardId).query(Long.class).single();
  }
}
