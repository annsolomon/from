# ADR-0001: Toolchain and platform versions

- **Status:** Accepted (pending first `verify.sh full` evidence in Codespaces)
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
