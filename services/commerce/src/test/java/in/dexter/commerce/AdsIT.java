package in.dexter.commerce;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.commerce.support.CommerceIT;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Ads (MASTER_PLAN P17): campaign CRUD, weighted rotation, per-visitor frequency caps that hold under concurrency
 * (the named test), ad-free from billing's entitlements, and the ad click redirect. Serving draws from every live
 * campaign of the placement, so the cap tests use a campaign whose weight dwarfs any other test's.
 */
class AdsIT extends CommerceIT {

  static String campaign(String name, String placement, int weight, int cap, int creatives) {
    StringBuilder k = new StringBuilder("[");
    for (int i = 0; i < creatives; i++) {
      k.append(i == 0 ? "" : ",").append("{\"headline\":\"").append(name).append(" ").append(i)
          .append("\",\"body\":\"b\",\"clickUrl\":\"https://advertiser.example/").append(i).append("\",\"weight\":1}");
    }
    k.append(']');
    return "{\"name\":\"" + name + "\",\"advertiser\":\"Mock Ads\",\"weight\":" + weight + ",\"frequencyCap\":" + cap
        + ",\"frequencyWindowSeconds\":3600,\"placements\":[\"" + placement + "\"],\"creatives\":" + k + "}";
  }

  UUID create(String body) {
    Res r = admin("POST", "/v1/admin/commerce/campaigns", body);
    if (r.status() != 201) {
      throw new AssertionError("campaign: " + r);
    }
    return UUID.fromString(r.json().get("id").asString());
  }

  Res serve(String placement, UUID viewer, String ua) {
    return viewer == null ? send("GET", "/v1/ads/serve?placement=" + placement, null, "User-Agent", ua)
        : send("GET", "/v1/ads/serve?placement=" + placement, null, auth(viewer, "User-Agent", ua));
  }

  /** Serves until the dominant campaign is capped for this visitor; counts its ads. */
  static int servedOf(List<Res> results, UUID campaign) {
    int n = 0;
    for (Res r : results) {
      JsonNode ad = r.json().get("ad");
      if (ad != null && !ad.isNull() && ad.get("campaignId").asString().equals(campaign.toString())) {
        n++;
      }
    }
    return n;
  }

  /** MASTER_PLAN P17 named test: frequency cap. */
  @Test
  void theFrequencyCapHoldsPerVisitorAndUnderConcurrency() throws Exception {
    UUID c = create(campaign("Capped", "dashboard", 1000, 3, 1));
    UUID viewer = UUID.randomUUID();
    List<Res> seq = new ArrayList<>();
    for (int i = 0; i < 6; i++) {
      Res r = serve("dashboard", viewer, "cap-agent");
      assertThat(r.status()).as(r.toString()).isEqualTo(200);
      seq.add(r);
    }
    assertThat(servedOf(seq, c)).as("three impressions per window, then the campaign leaves the draw").isEqualTo(3);
    assertThat(servedOf(List.of(serve("dashboard", UUID.randomUUID(), "cap-agent")), c))
        .as("another visitor has their own cap").isEqualTo(1);

    UUID racer = UUID.randomUUID();
    ExecutorService pool = Executors.newFixedThreadPool(10);
    List<Future<Res>> fs = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      fs.add(pool.submit(() -> serve("dashboard", racer, "race-agent")));
    }
    List<Res> raced = new ArrayList<>();
    for (Future<Res> f : fs) {
      raced.add(f.get());
    }
    pool.shutdown();
    assertThat(servedOf(raced, c)).as("ten concurrent serves never exceed the cap").isEqualTo(3);
    long impressions = count("SELECT count(1) FROM commerce.ad_impressions_raw WHERE campaign_id = ?"
        + " AND account_id = ?", c, racer);
    assertThat(impressions).isEqualTo(3);

    // anonymous visitors are capped by (ip, user agent)
    List<Res> anon = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      anon.add(serve("dashboard", null, "anon-" + c));
    }
    assertThat(servedOf(anon, c)).isEqualTo(3);
  }

  @Test
  void adFreeViewersGetNoAd() {
    create(campaign("For everyone", "home", 10, 100, 1));
    UUID premium = UUID.randomUUID();
    assertThat(serve("home", premium, "af").json().get("ad").isNull()).as("FREE (no entitlement yet) sees ads")
        .isFalse();
    entitlements(premium, "PREMIUM", true, 1);
    Res r = serve("home", premium, "af");
    assertThat(r.json().get("ad").isNull()).isTrue();
    assertThat(r.json().get("reason").asString()).isEqualTo("AD_FREE");
    entitlements(premium, "FREE", false, 2);
    assertThat(serve("home", premium, "af").json().get("ad").isNull()).as("back on FREE").isFalse();
    entitlements(premium, "PREMIUM", true, 1);
    assertThat(serve("home", premium, "af").json().get("ad").isNull()).as("a stale event changes nothing")
        .isFalse();
    assertThat(serve("nowhere", null, "x").code()).isEqualTo("PLACEMENT_UNKNOWN");
  }

  @Test
  void weightedRotationAcrossCreatives() {
    UUID c = create(campaign("Rotating", "deal-detail", 1000, 1000, 2));
    Map<String, Integer> seen = new HashMap<>();
    UUID viewer = UUID.randomUUID();
    for (int i = 0; i < 40; i++) {
      JsonNode ad = serve("deal-detail", viewer, "rot").json().get("ad");
      if (!ad.isNull() && ad.get("campaignId").asString().equals(c.toString())) {
        seen.merge(ad.get("creativeId").asString(), 1, Integer::sum);
      }
    }
    assertThat(seen).as("both equal-weight creatives are served").hasSize(2);
  }

  @Test
  void campaignCrudAndTheAdClick() {
    UUID c = create(campaign("Crud", "home", 1, 50, 1));
    Res got = admin("GET", "/v1/admin/commerce/campaigns/" + c, null);
    assertThat(got.status()).isEqualTo(200);
    assertThat(got.header("ETag")).isEqualTo("\"0\"");
    String creative = got.json().get("creatives").get(0).get("id").asString();
    Res bad = admin("PUT", "/v1/admin/commerce/campaigns/" + c, campaign("Crud", "nowhere", 1, 50, 0)
        .replace(",\"creatives\":[]", ""), "If-Match", "\"0\"");
    assertThat(bad.status()).isEqualTo(400);
    assertThat(bad.body()).contains("PLACEMENT_UNKNOWN");
    Res put = admin("PUT", "/v1/admin/commerce/campaigns/" + c, campaign("Crud v2", "home", 2, 50, 0)
        .replace(",\"creatives\":[]", ""), "If-Match", "\"0\"");
    assertThat(put.status()).as(put.toString()).isEqualTo(200);
    assertThat(put.json().get("name").asString()).isEqualTo("Crud v2");
    assertThat(put.json().get("version").asInt()).isEqualTo(1);
    Res added = admin("POST", "/v1/admin/commerce/campaigns/" + c + "/creatives",
        "{\"headline\":\"Second\",\"clickUrl\":\"https://advertiser.example/2\",\"weight\":3}");
    assertThat(added.status()).as(added.toString()).isEqualTo(201);
    assertThat(added.json().get("creatives")).hasSize(2);
    Res pause = admin("PUT", "/v1/admin/commerce/campaigns/" + c + "/creatives/" + creative, "{\"status\":\"paused\"}",
        "If-Match", "\"0\"");
    assertThat(pause.status()).as(pause.toString()).isEqualTo(200);

    // an ad click redirects to the creative's URL and is counted
    String clickPath = null;
    for (int i = 0; i < 200 && clickPath == null; i++) {
      JsonNode ad = serve("home", UUID.randomUUID(), "click").json().get("ad");
      if (!ad.isNull() && ad.get("campaignId").asString().equals(c.toString())) {
        clickPath = ad.get("clickPath").asString();
      }
    }
    assertThat(clickPath).as("the campaign was served at least once in 200 draws").isNotNull();
    Res click = send("GET", clickPath, null);
    assertThat(click.status()).isEqualTo(302);
    assertThat(click.header("Location")).isEqualTo("https://advertiser.example/2");
    flusher.flush();
    Res stats = admin("GET", "/v1/admin/commerce/stats", null);
    JsonNode row = null;
    for (JsonNode s : stats.json().get("campaigns")) {
      if (s.get("campaignId").asString().equals(c.toString())) {
        row = s;
      }
    }
    assertThat(row).isNotNull();
    assertThat(row.get("clicks").asLong()).isEqualTo(1);
    assertThat(row.get("impressions").asLong()).isPositive();

    assertThat(send("DELETE", "/v1/admin/commerce/campaigns/" + c, null, authWith(operator())).status())
        .isEqualTo(204);
    assertThat(admin("GET", "/v1/admin/commerce/campaigns/" + c, null).json().get("status").asString())
        .isEqualTo("ended");
    assertThat(send("GET", clickPath, null).code()).as("an ended campaign's link").isEqualTo("AD_NOT_FOUND");
  }
}
