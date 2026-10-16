package in.dexter.lab.p02;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code dexter.lab.p02.*} (dev only). Defaults match {@code .env.example}; ledger's {@code application.yml} maps the
 * {@code .env} variables that {@code run.sh} exports. Roles {@code lab_owner/app/relay} and the database {@code lab}
 * come from {@code infra/postgres/apply-roles.sh}; the Redis user {@code lab} from {@code infra/redis/render-acl.sh}.
 */
@ConfigurationProperties("dexter.lab.p02")
public record LabProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("jdbc:postgresql://localhost:5432/lab") String jdbcUrl,
    @DefaultValue("dev_owner_pw") String ownerPassword,
    @DefaultValue("dev_app_pw") String appPassword,
    @DefaultValue("dev_relay_pw") String relayPassword,
    @DefaultValue("localhost:9092") String kafkaBootstrap,
    @DefaultValue("localhost") String redisHost,
    @DefaultValue("6379") int redisPort,
    @DefaultValue("dev_redis_svc_pw") String redisPassword) {}
