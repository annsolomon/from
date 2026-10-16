package in.dexter.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

/** P00 smoke: the context loads on a random port and /actuator/health reports UP. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayHealthSmokeTest {

  @Value("${local.server.port}")
  int port;

  @Test
  void contextLoadsAndHealthIsUp() throws Exception {
    try (HttpClient http = HttpClient.newHttpClient()) {
      HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health"))
          .GET().build();
      HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
      assertThat(res.statusCode()).isEqualTo(200);
      assertThat(res.body()).contains("\"status\":\"UP\"");
    }
  }
}
