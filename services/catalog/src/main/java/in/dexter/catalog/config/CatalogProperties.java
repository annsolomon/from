package in.dexter.catalog.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** {@code dexter.catalog.*}. */
@ConfigurationProperties("dexter.catalog")
public record CatalogProperties(@DefaultValue Admin admin, @DefaultValue Relay relay) {

  /** The catalog_admin pool (admin endpoints; RLS lets only editor roles in). Blank URL = the app pool's URL. */
  public record Admin(String dbUrl, @DefaultValue("catalog_admin") String dbUsername, String dbPassword,
      @DefaultValue("5") int dbPoolSize) {}

  /** Outbox relay (MASTER_PLAN 6.3) as catalog_relay, plus topic creation. */
  public record Relay(@DefaultValue("false") boolean enabled, String url, String username, String password,
      String bootstrapServers, @DefaultValue("3") int partitions, @DefaultValue("1") short replication) {}
}
