package in.dexter.cache.autoconfigure;

import in.dexter.cache.CacheSettings;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** {@code dexter.cache.*}; {@code service} defaults to {@code spring.application.name}. */
@ConfigurationProperties("dexter.cache")
public record DexterCacheProperties(
    @Nullable String service,
    @DefaultValue("5s") Duration lockTtl,
    @DefaultValue("150ms") Duration lockWait,
    @DefaultValue("30s") Duration negativeTtl,
    @DefaultValue("0.1") double jitter,
    @DefaultValue("1s") Duration breakerOpen,
    @DefaultValue("7d") Duration genTtl,
    @DefaultValue("262144") int maxValueBytes,
    @DefaultValue("1.0") double earlyRefreshBeta) {

  CacheSettings settings() {
    return new CacheSettings(lockTtl, lockWait, negativeTtl, jitter, breakerOpen, genTtl, maxValueBytes,
        earlyRefreshBeta);
  }
}
