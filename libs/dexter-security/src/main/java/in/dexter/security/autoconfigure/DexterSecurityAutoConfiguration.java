package in.dexter.security.autoconfigure;

import in.dexter.core.time.Clocks;
import in.dexter.security.client.ServiceTokenClient;
import in.dexter.security.jwt.HttpJwksSource;
import in.dexter.security.jwt.JwksCache;
import in.dexter.security.jwt.JwtSettings;
import in.dexter.security.jwt.JwtVerifier;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import tools.jackson.databind.json.JsonMapper;

/**
 * Builds the {@link JwtVerifier} when {@code dexter.security.jwks-uri} is set, and the
 * {@link ServiceTokenClient} when {@code dexter.security.client.token-uri} is set. A service (or a
 * test) may define its own {@code JwtVerifier} bean instead; the web wiring
 * ({@link DexterSecurityWebAutoConfiguration}) follows whichever verifier exists.
 */
@AutoConfiguration(afterName = "org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration")
@EnableConfigurationProperties(DexterSecurityProperties.class)
public class DexterSecurityAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnProperty(prefix = "dexter.security", name = "jwks-uri")
  JwtVerifier dexterJwtVerifier(DexterSecurityProperties props, Environment env, ObjectProvider<Clock> clock) {
    if (props.issuer() == null || props.issuer().isBlank()) {
      throw new IllegalStateException("dexter.security.issuer is required when dexter.security.jwks-uri is set");
    }
    String audience = props.audience() != null ? props.audience() : env.getProperty("spring.application.name");
    if (audience == null || audience.isBlank()) {
      throw new IllegalStateException("Set dexter.security.audience or spring.application.name");
    }
    Clock c = clock.getIfAvailable(Clocks::systemUtc);
    JwtSettings settings = new JwtSettings(props.issuer(), audience, props.tokenType(), props.clockSkew(),
        props.maxTokenLifetime());
    JwksCache keys = new JwksCache(new HttpJwksSource(props.jwksUri()), c, props.jwksTtl(),
        props.jwksMinRefetchInterval(), props.jwksMaxStale());
    return new JwtVerifier(settings, keys, c);
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnProperty(prefix = "dexter.security.client", name = "token-uri")
  ServiceTokenClient dexterServiceTokenClient(DexterSecurityProperties props, ObjectProvider<JsonMapper> json,
      ObjectProvider<Clock> clock) {
    DexterSecurityProperties.Client cl = props.client();
    if (cl.clientId() == null || cl.clientSecret() == null) {
      throw new IllegalStateException("dexter.security.client.client-id and client-secret are required with token-uri");
    }
    return new ServiceTokenClient(cl.tokenUri(), cl.clientId(), cl.clientSecret(), ServiceTokenClient.defaultHttpClient(),
        json.getIfAvailable(() -> JsonMapper.builder().build()), clock.getIfAvailable(Clocks::systemUtc), cl.refreshSkew());
  }
}
