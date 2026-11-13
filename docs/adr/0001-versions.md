# ADR-0001: Toolchain and platform versions

- **Status:** Accepted; amended 2026-11-13 (A-134: Boot 4.1.2 + Cloud 2025.1.4)
- **Date:** 2026-09-21 · **Phase:** P00

## Context

MASTER_PLAN 2.3 asks for Java 25, Spring Boot 4.0.x, Spring Cloud 2025.1.x, Testcontainers 2.x and Flyway 11,
with a fallback to Boot 3.5.x on Java 21.

State of the ecosystem on 2026-09-21, checked while writing this ADR:
- Spring Boot 4.0.8 is the newest 4.0 patch (2026-08-20). Boot 4.1.1 is also out.
- Spring Boot 4.0 open-source support ends **2026-12-31**. Spring Boot 3.5 reached end of life on 2026-06-30.
- Spring Cloud 2025.1.x (Oakwood) targets Boot 4.0.x, and 2025.1.3 is its current release.
  The next train, 2026.0 (Paddington), is being built on Boot 4.2. No train officially targets Boot 4.1,
  and users report the 2025.1 compatibility verifier rejecting Boot 4.1.

## Decision

| Item | Version | Where |
|---|---|---|
| Java | **25** (Temurin) | `platform-bom/pom.xml` → `java.version` |
| Spring Boot | **4.0.8** | parent of `platform-bom` |
| Spring Cloud | **2025.1.3** | `spring-cloud.version` |
| Flyway, Testcontainers, Kafka clients, Jackson 3, Micrometer, JUnit, PG JDBC | managed by Boot 4.0.8 | not overridden |
| Maven | ≥ 3.9.6 (devcontainer installs 3.9.11) | enforcer rule |
| Postgres | 17 | `infra/compose` |
| Kafka | 4.1.0 (KRaft) | `infra/compose` |
| Redis | 7.4 | `infra/compose` |

- **Fallback is not Boot 3.5.** It is end-of-life. If Java 25 breaks a plugin, the fallback is
  `java.version=21` on the same Boot 4.0.8. That is one property change.
- **Upgrade trigger:** when Spring Cloud 2026.0 and Boot 4.2 are both GA (expected ~Nov 2026), upgrade in a
  dedicated step before 2026-12-31. Record it as a Plan Amendment.
- Third-party libraries that Boot does not manage are pinned in the phase that first uses them
  (Plan Amendment A-02). That way every pin is verified against Maven Central at the time.

## Consequences

- The Boot 4 modular starters are used (`spring-boot-starter-webmvc`, etc.).
  The gateway uses `spring-cloud-starter-gateway-server-webflux`.
- `dependencyConvergence` is enforced from day one, and can be relaxed with `-Ddexter.enforce.convergence=false`
  only for diagnosis. That property must never be committed as false.
- Nothing is proven compiled until a `verify-report.md` from Codespaces shows it.

## Amendment 2026-11-13 (P19, A-134): Spring Boot 4.1.2 + Spring Cloud 2025.1.4

The upgrade trigger above (Boot 4.2 and Cloud 2026.0 GA) had not fired by the end of P19 — the newest tags were
`v4.2.0-RC2` and `v2026.0.0-RC2` — while Boot 4.0's open-source support ends on 2026-12-31. The release moves to the
supported 4.1 line instead: **Spring Boot 4.1.2** (Spring Framework 7.0.10, Jackson 3.1.7 / 2.21.7, Tomcat 11.0.27,
Flyway 12.4, Kafka clients 4.2.2, Lettuce 7.5, Spring Data 2026.0, Micrometer 1.17, OpenTelemetry 1.62,
Testcontainers 2.0.5) and **Spring Cloud 2025.1.4**, whose compatibility verifier (spring-cloud-commons 5.0.4)
accepts Boot 4.0.x and 4.1.x. One code change: reco names its Redis listener container, because Boot 4.1
auto-configures a second (annotation-driven) one. The P19 Jackson / Tomcat security pins were removed (Boot manages
the fixed versions; the Tomcat pin would have been a downgrade). The 4.2 / 2026.0 move remains the next platform step
once both are GA.
