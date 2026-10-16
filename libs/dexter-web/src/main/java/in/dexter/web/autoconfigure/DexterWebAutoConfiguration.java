package in.dexter.web.autoconfigure;

import in.dexter.web.dev.P01LabController;
import in.dexter.web.error.ProblemDetailsAdvice;
import in.dexter.web.etag.IfMatchArgumentResolver;
import in.dexter.web.json.StrictJsonCustomizer;
import in.dexter.web.page.PageRequestArgumentResolver;
import in.dexter.web.request.RequestIdFilter;
import java.util.List;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Wires dexter-web into every servlet service that has it on the classpath. */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class DexterWebAutoConfiguration {

  @Bean
  FilterRegistrationBean<RequestIdFilter> dexterRequestIdFilter() {
    FilterRegistrationBean<RequestIdFilter> reg = new FilterRegistrationBean<>(new RequestIdFilter());
    reg.setOrder(Ordered.HIGHEST_PRECEDENCE);
    reg.addUrlPatterns("/*");
    reg.setName("dexterRequestIdFilter");
    return reg;
  }

  @Bean
  @ConditionalOnMissingBean
  ProblemDetailsAdvice dexterProblemDetailsAdvice() {
    return new ProblemDetailsAdvice();
  }

  @Bean
  StrictJsonCustomizer dexterStrictJsonCustomizer() {
    return new StrictJsonCustomizer();
  }

  @Bean
  WebMvcConfigurer dexterWebMvcConfigurer() {
    return new WebMvcConfigurer() {
      @Override
      public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new PageRequestArgumentResolver());
        resolvers.add(new IfMatchArgumentResolver());
      }
    };
  }

  /** Dev-only trail lab (MASTER_PLAN 11.2). Never active outside the {@code dev} profile. */
  @Bean
  @Profile("dev")
  P01LabController dexterP01LabController() {
    return new P01LabController();
  }
}
