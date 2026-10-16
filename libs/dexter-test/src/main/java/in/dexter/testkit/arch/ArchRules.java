package in.dexter.testkit.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaParameter;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.EvaluationResult;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.Architectures;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The architecture rules every service obeys (MASTER_PLAN 2.5, 7.2, ADR-0004 §9, ADR-0007), as plain ArchUnit
 * rules (core artifact, no JUnit engine). A service's {@code ArchitectureTest} calls
 * {@code ArchRules.check(ArchRules.importMain("in.dexter.<svc>"), "in.dexter.<svc>")}; the demo module checks
 * itself in {@code DemoArchitectureTest}.
 *
 * <p>Layers ({@code <base>.api|app|domain|infra|config}): api → app; app → domain, infra, config; infra → app,
 * domain (consumers call use-cases); config wires everything. Classes outside the five layer packages (the
 * application class, tests) are not constrained by the layer rule.
 */
public final class ArchRules {
  static final String PUBLIC = "in.dexter.security.web.PublicEndpoint";
  static final String PERMISSION = "in.dexter.security.web.RequiresPermission";
  static final String STEP_UP = "in.dexter.security.web.RequiresStepUp";
  static final String INTERNAL = "in.dexter.security.web.InternalEndpoint";
  static final String HANDLER = "in.dexter.events.consumer.EventConsumer$Handler";
  static final String REQUEST_HEADER = "org.springframework.web.bind.annotation.RequestHeader";
  /** Headers that claim an identity. Identity comes only from the verified token (MASTER_PLAN 7.2, ADR-0007). */
  static final Pattern IDENTITY_HEADER = Pattern.compile(
      "(?i)authorization|x-(account|user|tenant|principal|actor|role|roles|perm|perms|permissions|admin|demo-account|"
          + "auth|client|client-id|service|subject|sub)([-_].*)?");

  private ArchRules() {}

  /** The service's production classes (no tests, no jars). */
  public static JavaClasses importMain(String basePackage) {
    return new ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
        .importPackages(basePackage);
  }

  /** All rules for a service rooted at {@code base}. */
  public static List<ArchRule> all(String base) {
    return List.of(layers(base), domainIsPure(base), jdbcOnlyInInfraAndConfig(base), noCacheInApiOrDomain(base),
        cacheInvalidationOnlyFromAppOrConsumers(base), transactionsOnlyInAppOrConsumers(base),
        securityAnnotationsOnlyInApi(base), publicEndpointNotCombined(base), noClientIdentityHeaders(base),
        noRawHeaderReads(base));
  }

  /** Evaluates every rule and fails once with all violations. */
  public static void check(JavaClasses classes, String base) {
    List<String> failures = new ArrayList<>();
    for (ArchRule rule : all(base)) {
      EvaluationResult r = rule.evaluate(classes);
      if (r.hasViolation()) {
        failures.add(r.getFailureReport().toString());
      }
    }
    if (!failures.isEmpty()) {
      throw new AssertionError("Architecture rules violated (MASTER_PLAN 2.5):\n" + String.join("\n\n", failures));
    }
  }

  private static String[] layerPackages(String base, String... layers) {
    String[] out = new String[layers.length];
    for (int i = 0; i < layers.length; i++) {
      out[i] = base + "." + layers[i] + "..";
    }
    return out;
  }

  public static ArchRule layers(String base) {
    return Architectures.layeredArchitecture()
        .consideringOnlyDependenciesInLayers()
        .withOptionalLayers(true)
        .layer("api").definedBy(base + ".api..")
        .layer("app").definedBy(base + ".app..")
        .layer("domain").definedBy(base + ".domain..")
        .layer("infra").definedBy(base + ".infra..")
        .layer("config").definedBy(base + ".config..")
        .whereLayer("api").mayOnlyBeAccessedByLayers("config")
        .whereLayer("app").mayOnlyBeAccessedByLayers("api", "infra", "config")
        .whereLayer("infra").mayOnlyBeAccessedByLayers("app", "config")
        .whereLayer("domain").mayOnlyBeAccessedByLayers("api", "app", "infra", "config")
        .as("layers: api -> app -> domain/infra; config wires (MASTER_PLAN 2.5)");
  }

  /** domain: pure logic, no Spring, no JDBC, no Dexter infrastructure libraries, no JSON annotations. */
  public static ArchRule domainIsPure(String base) {
    return noClasses().that().resideInAPackage(base + ".domain..")
        .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "java.sql..", "javax.sql..",
            "jakarta.servlet..", "tools.jackson..", "com.fasterxml.jackson..", "org.apache.kafka..",
            "in.dexter.db..", "in.dexter.web..", "in.dexter.events..", "in.dexter.idempotency..", "in.dexter.cache..",
            "in.dexter.security..")
        .as("domain imports nothing from Spring, JDBC or infrastructure (MASTER_PLAN 2.5)")
        .allowEmptyShould(true);
  }

  /** JdbcClient (and any JDBC) only in infra (repositories, consumers) and config (wiring). */
  public static ArchRule jdbcOnlyInInfraAndConfig(String base) {
    return noClasses().that().resideInAnyPackage(layerPackages(base, "api", "app", "domain"))
        .should().dependOnClassesThat().resideInAnyPackage("org.springframework.jdbc..", "java.sql..", "javax.sql..")
        .as("JdbcClient only in infra (controllers and use-cases never touch it)")
        .allowEmptyShould(true);
  }

  /** Controllers and domain never touch the cache (ADR-0004 §9). */
  public static ArchRule noCacheInApiOrDomain(String base) {
    return noClasses().that().resideInAnyPackage(layerPackages(base, "api", "domain"))
        .should().dependOnClassesThat().resideInAPackage("in.dexter.cache..")
        .as("api and domain never use dexter-cache (ADR-0004 §9)")
        .allowEmptyShould(true);
  }

  /** Generation bumps come from use-cases (Path A) or event consumers (Path B / projections), A-21. */
  public static ArchRule cacheInvalidationOnlyFromAppOrConsumers(String base) {
    return noClasses().that().resideInAnyPackage(layerPackages(base, "api", "domain", "infra"))
        .and().areNotAssignableTo(HANDLER)
        .should().accessClassesThat().areAssignableTo("in.dexter.cache.CacheInvalidator")
        .as("CacheInvalidator only from app use-cases or event consumers (ADR-0004 §9, A-21)")
        .allowEmptyShould(true);
  }

  /** Transactions start in app; event consumers (infra) run one transaction per event. No @Transactional. */
  public static ArchRule transactionsOnlyInAppOrConsumers(String base) {
    return noClasses().that().resideInAnyPackage(layerPackages(base, "api", "domain", "infra"))
        .and().areNotAssignableTo(HANDLER)
        .should().accessClassesThat().areAssignableTo("in.dexter.db.tenant.TenantTx")
        .orShould().accessClassesThat().areAssignableTo("org.springframework.transaction.support.TransactionTemplate")
        .orShould().beMetaAnnotatedWith("org.springframework.transaction.annotation.Transactional")
        .orShould(com.tngtech.archunit.lang.conditions.ArchConditions.be(
            JavaClass.Predicates.containAnyMethodsThat(annotatedTransactional())))
        .as("transactions start only in app (MASTER_PLAN 2.5); consumers own one transaction per event")
        .allowEmptyShould(true);
  }

  private static com.tngtech.archunit.base.DescribedPredicate<JavaMethod> annotatedTransactional() {
    return com.tngtech.archunit.base.DescribedPredicate.describe("are annotated with @Transactional",
        m -> m.isMetaAnnotatedWith("org.springframework.transaction.annotation.Transactional"));
  }

  /** Security annotations are enforced by the MVC interceptor, so anywhere but a controller they do nothing. */
  public static ArchRule securityAnnotationsOnlyInApi(String base) {
    return noMethods().that().areDeclaredInClassesThat().resideInAPackage(base + "..")
        .and().areDeclaredInClassesThat().resideOutsideOfPackage(base + ".api..")
        .should().beMetaAnnotatedWith(PERMISSION)
        .orShould().beMetaAnnotatedWith(STEP_UP)
        .orShould().beMetaAnnotatedWith(PUBLIC)
        .orShould().beMetaAnnotatedWith(INTERNAL)
        .as("@RequiresPermission/@RequiresStepUp/@PublicEndpoint/@InternalEndpoint only in api (A-17)")
        .allowEmptyShould(true)
        .because("the interceptor reads them on controller handlers only; elsewhere they silently protect nothing");
  }

  /** The interceptor rejects the combination at the first request; this makes it a build failure. */
  public static ArchRule publicEndpointNotCombined(String base) {
    return methods().that().areDeclaredInClassesThat().resideInAPackage(base + "..")
        .should(new ArchCondition<JavaMethod>("not combine @PublicEndpoint with a security requirement") {
          @Override
          public void check(JavaMethod m, ConditionEvents events) {
            JavaClass owner = m.getOwner();
            boolean pub = m.isMetaAnnotatedWith(PUBLIC) || owner.isMetaAnnotatedWith(PUBLIC);
            boolean requires = false;
            for (String a : List.of(PERMISSION, STEP_UP, INTERNAL)) {
              requires |= m.isMetaAnnotatedWith(a) || owner.isMetaAnnotatedWith(a);
            }
            if (pub && requires) {
              events.add(SimpleConditionEvent.violated(m, m.getFullName()
                  + " combines @PublicEndpoint with @RequiresPermission/@RequiresStepUp/@InternalEndpoint"));
            }
          }
        })
        .allowEmptyShould(true);
  }

  /** No {@code @RequestHeader} that claims an identity, and no all-headers map. */
  public static ArchRule noClientIdentityHeaders(String base) {
    return methods().that().areDeclaredInClassesThat().resideInAPackage(base + "..")
        .should(new ArchCondition<JavaMethod>("not read identity headers sent by the client") {
          @Override
          public void check(JavaMethod m, ConditionEvents events) {
            for (JavaParameter p : m.getParameters()) {
              for (JavaAnnotation<JavaParameter> a : p.getAnnotations()) {
                if (!a.getRawType().getName().equals(REQUEST_HEADER)) {
                  continue;
                }
                String name = headerName(a);
                String type = p.getRawType().getName();
                if (name.isEmpty() && isMapType(type)) {
                  events.add(SimpleConditionEvent.violated(m, m.getFullName()
                      + " reads all request headers; declare each header with @RequestHeader(\"Name\")"));
                } else if (IDENTITY_HEADER.matcher(name).matches()) {
                  events.add(SimpleConditionEvent.violated(m, m.getFullName() + " reads header '" + name
                      + "'; the caller's identity comes only from the verified token (AuthContext/Tenant)"));
                } else if (name.isEmpty()) {
                  events.add(SimpleConditionEvent.violated(m, m.getFullName()
                      + " has @RequestHeader without an explicit name; name it so it can be checked"));
                }
              }
            }
          }
        })
        .allowEmptyShould(true);
  }

  static String headerName(JavaAnnotation<?> a) {
    for (String prop : List.of("value", "name")) {
      Optional<Object> v = a.get(prop);
      if (v.isPresent() && !String.valueOf(v.get()).isEmpty()) {
        return String.valueOf(v.get());
      }
    }
    return "";
  }

  private static boolean isMapType(String type) {
    return type.equals("java.util.Map") || type.equals("org.springframework.util.MultiValueMap")
        || type.equals("org.springframework.http.HttpHeaders");
  }

  /** Headers are read declaratively (so {@link #noClientIdentityHeaders} can see the name). */
  public static ArchRule noRawHeaderReads(String base) {
    return noClasses().that().resideInAnyPackage(layerPackages(base, "api", "app", "domain", "infra"))
        .should().callMethod("jakarta.servlet.http.HttpServletRequest", "getHeader", "java.lang.String")
        .orShould().callMethod("jakarta.servlet.http.HttpServletRequest", "getHeaders", "java.lang.String")
        .orShould().callMethod("jakarta.servlet.http.HttpServletRequest", "getHeaderNames")
        .as("no raw HttpServletRequest header reads in services; use @RequestHeader(\"Name\")")
        .allowEmptyShould(true);
  }
}
