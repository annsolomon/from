package in.dexter.security.autoconfigure;

import in.dexter.core.time.Clocks;
import in.dexter.security.jwt.JwtVerifier;
import in.dexter.security.web.AuthContextArgumentResolver;
import in.dexter.security.web.SecurityInterceptor;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Servlet wiring: the default-deny {@link SecurityInterceptor} on every MVC handler plus the
 * {@code AuthContext}/{@code Tenant} argument resolver. Active when a {@link JwtVerifier} bean exists
 * (from {@code dexter.security.jwks-uri} or defined by the service/test). Actuator endpoints use
 * their own handler mapping and are not intercepted; expose only {@code health} publicly.
 */
@AutoConfiguration(after = DexterSecurityAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnBean(JwtVerifier.class)
public class DexterSecurityWebAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  SecurityInterceptor dexterSecurityInterceptor(JwtVerifier verifier, DexterSecurityProperties props,
      ObjectProvider<Clock> clock) {
    return new SecurityInterceptor(verifier, clock.getIfAvailable(Clocks::systemUtc), props.stepUpMaxAge(),
        props.publicPaths());
  }

  @Bean
  WebMvcConfigurer dexterSecurityWebMvcConfigurer(SecurityInterceptor interceptor) {
    return new WebMvcConfigurer() {
      @Override
      public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).order(Integer.MIN_VALUE + 100);
      }

      @Override
      public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new AuthContextArgumentResolver());
      }
    };
  }
}
