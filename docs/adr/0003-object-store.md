# ADR-0003: Object store — SeaweedFS instead of MinIO

- **Status:** Accepted (pending pull + health evidence in Codespaces) · **Date:** 2026-09-21 · **Phase:** P00 r5

## Context

MASTER_PLAN P00 lists `minio` in the `core` profile. It is needed later for statement uploads (P12) and privacy
exports (P18). MinIO's community distribution is gone:
- binaries and images stopped in October 2025;
- the OSS repository was archived in February 2026;
- `minio/minio` and `minio/mc` were deleted from Docker Hub on 2026-09-11.

Our pinned tag failed to pull in Codespaces (verify run 4). The `quay.io` mirror is the same vendor's other
registry and could disappear the same way.

## Decision

- Use **SeaweedFS** (`chrislusf/seaweedfs:4.22`, compose service `objectstore`).
  It runs as a single container: `server -s3` starts master, volume, filer and the S3 gateway.
- The S3 endpoint is `http://localhost:8333`, bound to loopback only.
- Application code talks **only the S3 API** through an adapter, so the store stays swappable (Garage, RustFS, AWS S3, R2).
- Dev runs with no S3 identities (anonymous). **P12** adds an `-s3.config` identities file rendered from `.env`,
  plus bucket creation, before anything stores real data.

## Consequences

- `.env`: `MINIO_*` is replaced by `OBJECTSTORE_PORT`. The MinIO console (:9001) is gone.
- `verify.sh full` infra-checks now probe the S3 endpoint.
- Prod choice (managed S3/R2 vs self-hosted SeaweedFS on the VPS) is deferred to P19.
