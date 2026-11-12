# ADR-0027: Security hardening — distroless images, Trivy and ZAP in every full run, mTLS as an option

- Status: Proposed — P19 step 3; becomes Accepted with the CI run whose `full.image-scan` and `full.zap-baseline`
  pass (BUILD_README §3).
- Context: MASTER_PLAN P19 step 3: OWASP ZAP baseline against the gateway; dependency and image scans clean or
  waived in an ADR; mTLS option documented; pen-test checklist in `docs/security/`. MASTER_PLAN 7.x asks for
  distroless (Jib) images running as non-root. Until P19 there were no images, and the CI `trivy` job was a
  placeholder.

## Decisions

1. **Images from one Dockerfile, not Jib.** `infra/docker/service.Dockerfile`: `gcr.io/distroless/java25-debian13:nonroot`
   + the service's Spring Boot jar, `USER nonroot`, `-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError`. Jib was
   the plan; its main-class inference reads class files with ASM and lags new class-file versions (Java 25), and a
   plugin per module buys nothing the eight-line Dockerfile does not do. The image is built from the jar
   `mvn verify` already produced, so the scanned artefact is the tested one.
2. **Scans run where the artefacts are: in `verify.sh full`.** `full.image-scan` (`scripts/image-scan.sh`) builds
   the ten images, fails on any that runs as root, and runs Trivy on each for **HIGH/CRITICAL vulnerabilities that
   have a fix** (OS packages and every jar inside). Waivers live in `infra/security/trivyignore.yaml`, each with a
   statement and an expiry — none today. The CI `ci.yml` placeholder job is gone.
3. **Trivy is pinned and contained.** After the March 2026 supply-chain compromise of Trivy releases and of the
   `trivy-action` / `setup-trivy` tags (CVE-2026-33634), Trivy runs as `aquasec/trivy:0.72.0` (an explicit
   version, released after the incident), on a `docker save` tarball: no Docker socket, no GitHub token, no
   repository credentials inside the container. Moving the pin is a reviewed change.
4. **ZAP baseline as a signed-in user.** `full.zap-baseline` (`scripts/zap-baseline.sh`) runs
   `ghcr.io/zaproxy/zaproxy:2.16.1` `zap-baseline.py` against the gateway with a hook that adds a real bearer
   token and seeds two dozen API routes (a JSON API gives the spider nothing to follow), so the passive rules see
   authenticated and public responses. Rules in `infra/security/zap-rules.tsv`: headers, error disclosure, server
   banners, CORS and weak auth are **FAIL**; each IGNORE says why (HSTS on plain-HTTP local, epoch timestamps,
   CSRF on a bearer API). The scan fails unless ≥ 15 URLs reached ZAP (an empty scan is not a pass).
5. **mTLS: documented option, not default** (`docs/security/mtls.md`). The JWT on every call already authenticates
   and authorises; mTLS adds transport encryption and workload identity. Preferred via a mesh (strict mode);
   in-app via Spring Boot SSL bundles with Vault PKI certificates (`client-auth: need`, `reload-on-update`).
6. **Management endpoints off the public port in production** (`MANAGEMENT_SERVER_PORT`, docs/runbooks/deploy.md);
   dev endpoints and labs exist only in the `dev` profile (`ProdProfileIT` / `DevProfileIT`), and the release branch
   guard fails if the trail or dev beans come back.
7. **Pen-test checklist** (`docs/security/pentest-checklist.md`) maps each attack class to its defence and to the
   test that proves it today.

## Consequences

- Every full run costs ~5 more minutes (image builds, Trivy DB download, ZAP); in return a new HIGH/CRITICAL in a
  dependency or the base image fails the build the day a fix exists.
- Third-party infrastructure images (Postgres, Kafka, Vault, Grafana, ...) are not scanned here: they are not ours to
  patch; production pins them by digest and follows their advisories.
