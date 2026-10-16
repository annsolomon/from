package in.dexter.web;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.web.autoconfigure.DexterWebAutoConfiguration;
import in.dexter.web.dev.P01LabController;
import in.dexter.web.error.ProblemDetailsAdvice;
import in.dexter.web.json.StrictJsonCustomizer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

/** The dev lab exists only under the dev profile (MASTER_PLAN 11.2 guard test). */
class DexterWebAutoConfigurationTest {
  final WebApplicationContextRunner web = new WebApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(DexterWebAutoConfiguration.class));

  @Test
  void wiresAdviceFilterAndStrictJson() {
    web.run(ctx -> {
      assertThat(ctx).hasSingleBean(ProblemDetailsAdvice.class);
      assertThat(ctx).hasSingleBean(StrictJsonCustomizer.class);
      assertThat(ctx).hasBean("dexterRequestIdFilter");
    });
  }

  @Test
  void labOnlyInDevProfile() {
    web.run(ctx -> assertThat(ctx).doesNotHaveBean(P01LabController.class));
    web.withPropertyValues("spring.profiles.active=prod").run(ctx -> assertThat(ctx).doesNotHaveBean(P01LabController.class));
    web.withPropertyValues("spring.profiles.active=dev").run(ctx -> assertThat(ctx).hasSingleBean(P01LabController.class));
  }

  @Test
  void inactiveOutsideServletApps() {
    new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(DexterWebAutoConfiguration.class))
        .run(ctx -> assertThat(ctx).doesNotHaveBean(ProblemDetailsAdvice.class));
  }
}
