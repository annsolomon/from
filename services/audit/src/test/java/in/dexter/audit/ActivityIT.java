package in.dexter.audit;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.audit.support.AuditIT;
import in.dexter.contracts.events.gateway.ActivityRecordedV1;
import in.dexter.core.id.Ids;
import in.dexter.events.EventEnvelope;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Gateway activity (MASTER_PLAN P18): recorded once, aggregated per hour, read by operators on the dashboard. */
class ActivityIT extends AuditIT {

  EventEnvelope activity(UUID account, String service, String routeClass, int status, long ms) {
    return envelope(ActivityRecordedV1.TYPE, 1, "gateway", account, new ActivityRecordedV1("req-" + Ids.newId(),
        service, "/v1/x/**", routeClass, "GET", status, ms, Instant.now().toString()));
  }

  @Test
  void eachRequestCountsOnceAndTheDashboardAddsUp() {
    String svc = "svc" + Long.toString(System.nanoTime() % 1_000_000, 36);
    UUID a = Ids.newId();
    EventEnvelope first = activity(a, svc, "read", 200, 40);
    ingest.onActivity(first);
    ingest.onActivity(first); // a redelivery adds nothing
    ingest.onActivity(activity(a, svc, "read", 404, 10));
    ingest.onActivity(activity(null, svc, "write", 503, 250));
    assertThat(count("SELECT count(1) FROM audit.activity_events WHERE service = ?", svc)).isEqualTo(3);
    Instant hour = Instant.now().truncatedTo(ChronoUnit.HOURS);
    assertThat(rows("SELECT requests, client_errors, server_errors, total_ms, max_ms FROM audit.activity_hourly"
        + " WHERE service = ? AND route_class = 'read' AND hour = ?", svc, java.sql.Timestamp.from(hour)))
        .singleElement().satisfies(r -> {
          assertThat(((Number) r.get("requests")).longValue()).isEqualTo(2);
          assertThat(((Number) r.get("client_errors")).longValue()).isEqualTo(1);
          assertThat(((Number) r.get("total_ms")).longValue()).isEqualTo(50);
          assertThat(((Number) r.get("max_ms")).intValue()).isEqualTo(40);
        });

    Res dash = send("GET", "/v1/admin/activity/dashboard?hours=2", null, "Authorization",
        "Bearer " + operator("audit:read"));
    assertThat(dash.status()).as(dash.toString()).isEqualTo(200);
    JsonNode row = null;
    for (JsonNode s : dash.json().get("services")) {
      if (s.get("service").asString().equals(svc)) {
        row = s;
      }
    }
    assertThat(row).as("the service's row").isNotNull();
    assertThat(row.get("requests").asLong()).isEqualTo(3);
    assertThat(row.get("clientErrors").asLong()).isEqualTo(1);
    assertThat(row.get("serverErrors").asLong()).isEqualTo(1);
    assertThat(row.get("avgMs").asLong()).isEqualTo(100);
    assertThat(row.get("maxMs").asInt()).isEqualTo(250);
    assertThat(dash.json().get("hourly")).isNotEmpty();
    assertThat(dash.json().get("totals").get("requests").asLong()).isGreaterThanOrEqualTo(3);
    assertThat(send("GET", "/v1/admin/activity/dashboard?hours=0", null, "Authorization",
        "Bearer " + operator("audit:read")).status()).isEqualTo(400);
    assertThat(send("GET", "/v1/admin/activity/dashboard", null, "Authorization", "Bearer " + token(a)).status())
        .isEqualTo(403);
  }
}
