package in.dexter.testkit.containers;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * One Vault dev server (same image as compose) per test JVM (P19, ADR-0026): in-memory, unsealed, root token
 * {@link #ROOT_TOKEN}. {@link #api} talks to its HTTP API for setup (mounts, keys, policies, tokens).
 */
public final class TestVault {
  public static final String IMAGE = "hashicorp/vault:2.0";
  public static final String ROOT_TOKEN = "test-root-token";
  private static GenericContainer<?> vault;
  private static final HttpClient HTTP = HttpClient.newHttpClient();

  private TestVault() {}

  public static synchronized GenericContainer<?> container() {
    if (vault == null) {
      GenericContainer<?> c = new GenericContainer<>(DockerImageName.parse(IMAGE))
          .withExposedPorts(8200)
          .withEnv("VAULT_DEV_ROOT_TOKEN_ID", ROOT_TOKEN)
          .withEnv("VAULT_DEV_LISTEN_ADDRESS", "0.0.0.0:8200")
          .withEnv("SKIP_SETCAP", "true")
          .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withCapAdd(
              com.github.dockerjava.api.model.Capability.IPC_LOCK))
          .withCommand("server", "-dev")
          .waitingFor(Wait.forHttp("/v1/sys/health").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(2)));
      c.start();
      vault = c;
    }
    return vault;
  }

  public static String address() {
    return "http://" + container().getHost() + ":" + container().getMappedPort(8200);
  }

  /** One API call with {@code token}; returns status and body. {@code json} null = no body. */
  public static HttpResponse<String> api(String token, String method, String path, String json) {
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(address() + "/v1/" + path))
        .timeout(Duration.ofSeconds(10)).header("X-Vault-Token", token);
    b.method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
    try {
      return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new IllegalStateException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  /** Root call that must succeed (2xx). */
  public static String root(String method, String path, String json) {
    HttpResponse<String> r = api(ROOT_TOKEN, method, path, json);
    if (r.statusCode() / 100 != 2) {
      throw new IllegalStateException(method + " " + path + " -> " + r.statusCode() + " " + r.body());
    }
    return r.body();
  }
}
