package in.dexter.testkit.containers;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.Delay;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One Redis (same image as compose) per test JVM, plus the per-service ACL users of
 * {@code infra/redis/acl.tmpl} (the exact rule line the dev compose file renders), so cache tests run as the
 * service's restricted user and prove the ACL is sufficient.
 */
public final class TestRedis {
  public static final String IMAGE = "redis:7.4-alpine";
  /** MASTER_PLAN 8.2: Redis calls time out after 200 ms. */
  public static final Duration COMMAND_TIMEOUT = Duration.ofMillis(200);
  private static GenericContainer<?> redis;
  private static StringRedisTemplate admin;

  private TestRedis() {}

  public static synchronized GenericContainer<?> container() {
    if (redis == null) {
      GenericContainer<?> c = new GenericContainer<>(DockerImageName.parse(IMAGE)).withExposedPorts(6379);
      c.start();
      redis = c;
    }
    return redis;
  }

  public static String host() {
    return container().getHost();
  }

  public static int port() {
    return container().getMappedPort(6379);
  }

  /** The default (unrestricted) user: setup and assertions only. */
  public static synchronized StringRedisTemplate admin() {
    if (admin == null) {
      admin = new StringRedisTemplate(connectionFactory(host(), port(), null, null));
    }
    return admin;
  }

  /**
   * A connection factory as a service would configure it: 200 ms command timeout, commands rejected (not
   * queued) while disconnected, reconnect every 100 ms. {@code user} null = default user.
   */
  public static LettuceConnectionFactory connectionFactory(String host, int port, String user, String password) {
    RedisStandaloneConfiguration conf = new RedisStandaloneConfiguration(host, port);
    if (user != null) {
      conf.setUsername(user);
      conf.setPassword(RedisPassword.of(password));
    }
    ClientResources resources = ClientResources.builder().reconnectDelay(Delay.constant(Duration.ofMillis(100))).build();
    ClientOptions options = ClientOptions.builder()
        .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
        .build();
    LettuceClientConfiguration client = LettuceClientConfiguration.builder()
        .commandTimeout(COMMAND_TIMEOUT)
        .clientOptions(options)
        .clientResources(resources)
        .build();
    LettuceConnectionFactory f = new LettuceConnectionFactory(conf, client);
    f.afterPropertiesSet();
    f.start();
    return f;
  }

  /** Creates (or resets) the ACL user {@code service} from {@code infra/redis/acl.tmpl}. */
  public static void applyServiceAcl(String service, String password) {
    List<String> rules = aclRules(service, password);
    List<byte[]> args = new ArrayList<>();
    args.add(bytes("SETUSER"));
    args.add(bytes(service));
    args.add(bytes("reset"));
    rules.forEach(r -> args.add(bytes(r)));
    admin().execute((RedisCallback<Object>) c -> c.execute("ACL", args.toArray(new byte[0][])));
  }

  /** The rule tokens after {@code user <svc>} in the template, rendered for {@code service}. */
  public static List<String> aclRules(String service, String password) {
    String line = aclTemplateLine().replace("{{svc}}", service).replace("{{pw}}", password);
    List<String> tokens = new ArrayList<>(Arrays.asList(line.trim().split("\\s+")));
    return tokens.subList(2, tokens.size());
  }

  static String aclTemplateLine() {
    Path tmpl = repoFile("infra/redis/acl.tmpl");
    try {
      return Files.readAllLines(tmpl, StandardCharsets.UTF_8).stream()
          .filter(l -> l.startsWith("user {{svc}} "))
          .findFirst()
          .orElseThrow(() -> new IllegalStateException("no 'user {{svc}}' line in " + tmpl));
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Finds a file relative to the repository root (tests run with the module directory as working dir). */
  public static Path repoFile(String relative) {
    for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
      Path p = dir.resolve(relative);
      if (Files.isRegularFile(p)) {
        return p;
      }
    }
    throw new IllegalStateException(relative + " not found above " + Path.of("").toAbsolutePath());
  }

  /** Every key matching {@code pattern} (SCAN as the admin user). */
  public static Set<String> keys(String pattern) {
    Set<String> out = new TreeSet<>();
    try (Cursor<String> c = admin().scan(ScanOptions.scanOptions().match(pattern).count(1000).build())) {
      c.forEachRemaining(out::add);
    }
    return out;
  }

  private static byte[] bytes(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }
}
