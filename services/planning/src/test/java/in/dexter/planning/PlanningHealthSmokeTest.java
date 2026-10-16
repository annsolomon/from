package in.dexter.planning;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

/** P00 smoke (context loads, health UP) + P01 dexter-web wiring (request id, ProblemDetail). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PlanningHealthSmokeTest {

  @Value("${local.server.port}")
  int port;

  @Test
  void contextLoadsAndHealthIsUp() throws Exception {
    try (HttpClient http = HttpClient.newHttpClient()) {
      HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health"))
          .header("X-Request-Id", "smoke-test-1").GET().build();
      HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
      assertThat(res.statusCode()).isEqualTo(200);
      assertThat(res.body()).contains("\"status\":\"UP\"");
      // P01: dexter-web echoes X-Request-Id and answers unknown paths with a coded ProblemDetail
      assertThat(res.headers().firstValue("X-Request-Id")).hasValue("smoke-test-1");
      HttpResponse<String> nf = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/__nope"))
          .GET().build(), HttpResponse.BodyHandlers.ofString());
      assertThat(nf.statusCode()).isEqualTo(404);
      assertThat(nf.body()).contains("\"code\":\"NOT_FOUND\"").contains("\"requestId\":");
      assertThat(nf.headers().firstValue("X-Request-Id")).isPresent();
    }
  }
}
