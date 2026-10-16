# ADR-0005: Replica routing, cursor codec and PlanAssert without extra libraries

- Status: Accepted (P01). Proof pending the P01 Codespaces run (TenantTxIT, KeysetPagerIT, PlanAssertIT).
- Date: 2026-09-22

## Context
MASTER_PLAN 3.3 describes primary/replica routing with `AbstractRoutingDataSource` +
`LazyConnectionDataSourceProxy`. 4.2 defines a JSON cursor payload. 3.5 defines PlanAssert over
`EXPLAIN (FORMAT JSON)`.

## Decision
1. Routing uses Spring 6.1.2+ `LazyConnectionDataSourceProxy.setReadOnlyDataSource(replica)`
   (`DexterDataSources.readWriteRouting`). A read-only transaction (`TenantTx.read`) gets its
   physical connection from the replica pool, which must be configured read-only (Hikari
   `readOnly=true`). No custom routing class.
2. The cursor payload is encoded/parsed by a tiny strict JSON codec inside dexter-db (`MiniJson`,
   bounded size/depth). The HMAC is checked before any field is trusted, and both base64 parts must
   be canonical (otherwise the padding bits of the last character allow two spellings of one cursor).
3. PlanAssert analyses the plan with Postgres jsonpath (`jsonb_path_query`) instead of a Java JSON
   library, so dexter-db has no Jackson dependency.

## Consequences
- dexter-db depends only on spring-jdbc, spring-boot-autoconfigure and the Postgres driver.
- The replica pool's read-only default is a deployment requirement (documented in the runbook P19).
