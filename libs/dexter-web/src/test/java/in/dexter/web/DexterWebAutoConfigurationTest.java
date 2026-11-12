package in.dexter.web;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.web.autoconfigure.DexterWebAutoConfiguration;
import in.dexter.web.error.ProblemDetailsAdvice;
import in.dexter.web.json.StrictJsonCustomizer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

/** The library's web wiring; since the P19 release no dev lab in any profile. */
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
  void noDevLabInAnyProfile() {
    // P19 release: the P01 trail lab is gone; nothing in the library maps /_dev in any profile
    for (String profile : new String[] {"default", "prod", "dev"}) {
      web.withPropertyValues("spring.profiles.active=" + profile).run(ctx ->
          assertThat(ctx.getBeanNamesForAnnotation(org.springframework.web.bind.annotation.RestController.class))
              .isEmpty());
    }
  }

  @Test
  void inactiveOutsideServletApps() {
    new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(DexterWebAutoConfiguration.class))
        .run(ctx -> assertThat(ctx).doesNotHaveBean(ProblemDetailsAdvice.class));
  }
}
