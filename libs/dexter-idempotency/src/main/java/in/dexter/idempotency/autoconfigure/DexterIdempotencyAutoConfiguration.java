package in.dexter.idempotency.autoconfigure;

import in.dexter.idempotency.IdempotencyEngine;
import in.dexter.idempotency.web.IdempotencyKeyArgumentResolver;
import java.util.List;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wires the {@code @IdempotencyKey} resolver in servlet services, and the engine when the service has
 * a JdbcClient and names its schema ({@code dexter.db.schema}). The purge job needs the relay
 * DataSource and is scheduled by the service (P04/P07 wire it next to the outbox relay).
 */
@AutoConfiguration(afterName = {
    "org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration",
    "org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration"})
public class DexterIdempotencyAutoConfiguration {

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
  static class WebConfiguration {
    @Bean
    WebMvcConfigurer dexterIdempotencyWebMvcConfigurer() {
      return new WebMvcConfigurer() {
        @Override
        public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
          resolvers.add(new IdempotencyKeyArgumentResolver());
        }
      };
    }
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnBean({JdbcClient.class, JsonMapper.class})
  @ConditionalOnProperty(prefix = "dexter.db", name = "schema")
  static class EngineConfiguration {
    @Bean
    @ConditionalOnMissingBean
    IdempotencyEngine idempotencyEngine(JdbcClient jdbc, JsonMapper mapper, Environment env) {
      return new IdempotencyEngine(jdbc, mapper, env.getRequiredProperty("dexter.db.schema"));
    }
  }
}
