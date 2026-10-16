package in.dexter.testkit.arch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import org.junit.jupiter.api.Test;

/**
 * Each rule fails on the fixture package {@code archfixture.bad} for the right reason, and the demo template
 * (the code every service copies) passes all of them. Unit test: no Docker.
 */
class ArchRulesTest {
  static final String BAD = "in.dexter.testkit.archfixture.bad";
  static final String DEMO = "in.dexter.testkit.demo";
  static final JavaClasses bad = new ClassFileImporter().importPackages(BAD);
  static final JavaClasses demo = new ClassFileImporter().importPackages(DEMO);

  static String violations(ArchRule rule) {
    EvaluationResult r = rule.evaluate(bad);
    assertThat(r.hasViolation()).as(rule.getDescription()).isTrue();
    return String.join("\n", r.getFailureReport().getDetails());
  }

  @Test
  void theDemoTemplateObeysEveryRule() {
    assertThatCode(() -> ArchRules.check(demo, DEMO)).doesNotThrowAnyException();
  }

  @Test
  void checkReportsEveryBrokenRuleAtOnce() {
    assertThatThrownBy(() -> ArchRules.check(bad, BAD)).isInstanceOf(AssertionError.class)
        .hasMessageContaining("layers").hasMessageContaining("JdbcClient only in infra")
        .hasMessageContaining("domain imports nothing");
  }

  @Test
  void layers() {
    String v = violations(ArchRules.layers(BAD));
    assertThat(v).contains("BadService").contains("BadController");
  }

  @Test
  void domainIsPure() {
    assertThat(violations(ArchRules.domainIsPure(BAD))).contains("SpringyDomain").contains("StringUtils");
  }

  @Test
  void jdbcOnlyInInfra() {
    assertThat(violations(ArchRules.jdbcOnlyInInfraAndConfig(BAD))).contains("BadController").contains("JdbcClient");
  }

  @Test
  void noCacheInApi() {
    assertThat(violations(ArchRules.noCacheInApiOrDomain(BAD))).contains("BadController").contains("TenantCache");
  }

  @Test
  void cacheInvalidationOnlyFromAppOrConsumers() {
    String v = violations(ArchRules.cacheInvalidationOnlyFromAppOrConsumers(BAD));
    assertThat(v).contains("BadRepository.bumps").doesNotContain("GoodConsumer");
  }

  @Test
  void transactionsOnlyInAppOrConsumers() {
    String v = violations(ArchRules.transactionsOnlyInAppOrConsumers(BAD));
    assertThat(v).contains("BadRepository").doesNotContain("GoodConsumer");
  }

  @Test
  void securityAnnotationsOnlyInApi() {
    String v = violations(ArchRules.securityAnnotationsOnlyInApi(BAD));
    assertThat(v).contains("BadRepository.guarded").doesNotContain("BadController");
  }

  @Test
  void publicEndpointIsNeverCombined() {
    String v = violations(ArchRules.publicEndpointNotCombined(BAD));
    assertThat(v).contains("BadController.combined").doesNotContain("guarded");
  }

  @Test
  void noIdentityHeadersFromTheClient() {
    String v = violations(ArchRules.noClientIdentityHeaders(BAD));
    assertThat(v).contains("X-Account-Id").contains("Authorization").contains("reads all request headers")
        .doesNotContain("X-Request-Id");
  }

  @Test
  void noRawHeaderReads() {
    assertThat(violations(ArchRules.noRawHeaderReads(BAD))).contains("BadController.raw").contains("getHeader");
  }

  @Test
  void identityHeaderPattern() {
    for (String h : new String[] {"X-Account-Id", "x-user-id", "X-Tenant", "X-Roles", "X-Demo-Account", "Authorization",
        "X-Perms", "X-Service"}) {
      assertThat(ArchRules.IDENTITY_HEADER.matcher(h).matches()).as(h).isTrue();
    }
    for (String h : new String[] {"X-Request-Id", "Idempotency-Key", "If-Match", "X-Forwarded-For", "User-Agent",
        "X-Userland"}) {
      assertThat(ArchRules.IDENTITY_HEADER.matcher(h).matches()).as(h).isFalse();
    }
  }
}
