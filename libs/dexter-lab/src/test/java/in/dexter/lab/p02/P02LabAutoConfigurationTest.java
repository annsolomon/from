package in.dexter.lab.p02;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

/** The lab exists only under the dev profile with dexter.lab.p02.enabled=true, and connects to nothing at startup. */
class P02LabAutoConfigurationTest {
  final WebApplicationContextRunner web = new WebApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(P02LabAutoConfiguration.class));

  @Test
  void absentWithoutTheDevProfile() {
    web.withPropertyValues("dexter.lab.p02.enabled=true")
        .run(ctx -> assertThat(ctx).doesNotHaveBean(P02Lab.class).doesNotHaveBean(P02LabController.class));
  }

  @Test
  void absentInDevUnlessEnabled() {
    web.withPropertyValues("spring.profiles.active=dev")
        .run(ctx -> assertThat(ctx).doesNotHaveBean(P02Lab.class));
  }

  @Test
  void presentInDevWhenEnabledAndNotStartedYet() {
    web.withPropertyValues("spring.profiles.active=dev", "dexter.lab.p02.enabled=true",
            "dexter.lab.p02.jdbc-url=jdbc:postgresql://127.0.0.1:1/none")
        .run(ctx -> {
          assertThat(ctx).hasSingleBean(P02Lab.class).hasSingleBean(P02LabController.class);
          // nothing was contacted: the unreachable database would only fail on first use
          assertThat(ctx.getBean(P02Lab.class).status(false)).containsEntry("ready", false);
        });
  }
}
