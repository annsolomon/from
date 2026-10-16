package in.dexter.gateway;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import in.dexter.testkit.arch.ArchRules;
import org.junit.jupiter.api.Test;

/**
 * MASTER_PLAN 2.5 architecture rules for the gateway (A-22, A-26). The gateway is reactive (Spring Cloud Gateway on
 * WebFlux), so on top of the shared {@link ArchRules} it must never block an event-loop thread and never touch the
 * servlet API. The shared rules that name servlet types match by name only, so they need no servlet classes here.
 */
class GatewayArchitectureTest {
  static final String BASE = "in.dexter.gateway";

  @Test
  void followsTheSharedArchitectureRules() {
    JavaClasses classes = ArchRules.importMain(BASE);
    assertThat(classes.contain(GatewayApplication.class)).as("imported the gateway's own classes").isTrue();
    ArchRules.check(classes, BASE);
  }

  @Test
  void neverBlocksAndNeverUsesTheServletApi() {
    JavaClasses classes = ArchRules.importMain(BASE);
    noClasses().should().callMethodWhere(com.tngtech.archunit.base.DescribedPredicate.describe(
            "a blocking Reactor call (block*, toIterable, toStream)",
            (com.tngtech.archunit.core.domain.JavaMethodCall call) -> {
              String owner = call.getTargetOwner().getName();
              String name = call.getName();
              return (owner.equals("reactor.core.publisher.Mono") || owner.equals("reactor.core.publisher.Flux"))
                  && (name.startsWith("block") || name.equals("toIterable") || name.equals("toStream"));
            }))
        .orShould().dependOnClassesThat().resideInAnyPackage("jakarta.servlet..", "org.springframework.web.servlet..")
        .as("the gateway is non-blocking: no Mono/Flux.block*, no servlet API (MASTER_PLAN 3.10, 6)")
        .allowEmptyShould(true)
        .check(classes);
  }
}
