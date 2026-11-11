package in.dexter.privacy;

import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code dexter.privacy.*}: whether this service takes part in the privacy saga, which phases it runs, its Kafka,
 * and the object store its export parts go to.
 */
@ConfigurationProperties("dexter.privacy")
public record PrivacyProperties(boolean enabled, @Nullable String bootstrapServers, @Nullable List<String> phases,
    @Nullable Duration statementTimeout, int partitions, short replication, @Nullable Store store) {

  public PrivacyProperties {
    phases = phases == null || phases.isEmpty() ? List.of("erase", "export") : List.copyOf(phases);
    statementTimeout = statementTimeout == null ? Duration.ofMinutes(2) : statementTimeout;
    partitions = partitions <= 0 ? 3 : partitions;
    replication = replication <= 0 ? 1 : replication;
  }

  /** The S3-compatible store (SeaweedFS in dev) the export parts are written to. */
  public record Store(@Nullable String endpoint, @Nullable String bucket, @Nullable String accessKey,
      @Nullable String secretKey, @Nullable String region) {}
}
