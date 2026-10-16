package in.dexter.commerce;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import in.dexter.testkit.arch.ArchRules;
import org.junit.jupiter.api.Test;

/**
 * MASTER_PLAN 2.5 architecture rules (A-22): layers api → app → domain/infra, pure domain, JdbcClient only in infra,
 * transactions only in app, no cache in api/domain, security annotations only on controllers, no client identity
 * headers. Grows with the service: every class added under {@code in.dexter.commerce} is checked from now on.
 */
class CommerceArchitectureTest {
  static final String BASE = "in.dexter.commerce";

  @Test
  void followsTheArchitectureRules() {
    JavaClasses classes = ArchRules.importMain(BASE);
    assertThat(classes.contain(CommerceApplication.class)).as("imported the service's own classes").isTrue();
    ArchRules.check(classes, BASE);
  }
}
