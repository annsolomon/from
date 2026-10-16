# ADR-0008: Seed data — deterministic generator, staging before migrations, owner-role COPY

- Status: Accepted (P03). Proof: `SeedLoadIT`, `GeneratorTest`, `verify.sh full` steps `full.seed-medium` and
  `full.seed-determinism`.
- Context: MASTER_PLAN 11.1 asks for a deterministic generator (`--seed 42 --size small|medium|large`) that writes
  COPY files per service database and trail fixtures, loaded by `seed.sh` as the owner role. P03 runs **before
  any service has a table** (identity P04, ledger P07/P08, catalog P09, portfolio P11), yet the medium seed is
  needed for PlanAssert from P07 on and its timing is a P03 DoD item.

## Decision

1. **One Java CLI module `seed/`** (`dexter-seed`, an executable Boot-repackaged jar; no Spring at runtime).
   Commands `generate`, `load`, `checksum`. It is never a dependency of a service.
2. **Determinism by construction.**
   - PRNG = SplitMix64 implemented in the module (`Rng`), matching the published reference stream, so it never
     depends on the JDK.
   - Every logical stream is a fork keyed by a stable label (`account:<email>`, `account:bulk:0000123`, `fav:<cat>`).
     Adding a stream or changing a size never moves another stream, so **the demo accounts are byte-identical in
     every size** (`GeneratorTest.demoAccountsAreTheSameInEverySize`).
   - All time is anchored to a fixed window (2025-10-01 … 2026-09-30, Asia/Kolkata), never "now".
   - Transcendentals go through `StrictMath`.
   - No hash-ordered iteration reaches the output.
   - **Checksum** = SHA-256 over every table file and fixture (`manifest.json`). "Same seed → same checksum" is
     tested in unit tests and again in `verify.sh full` on the medium seed.
3. **Reference data is versioned CSV** in `seed/data/v1/`: categories, spend model, merchants, aliases, issuers,
   40 `MOCK_` card products, reward rules, milestones. It ships inside the jar.
   - Reference ids depend only on the data version, never on `--seed`, so a merchant or card has the same id in
     every dataset.
   - All issuers and cards are fictional and prefixed `MOCK_`. `RefData` refuses anything else.
   - A data change is a new directory (`v2`), not an edit, once a phase relies on v1 ids.
4. **Planned column lists are the contract** (`Tables`): each seeded table carries the phase whose migration
   creates it and the columns MASTER_PLAN §13 gives it. When that phase's migration differs, the phase updates
   `Tables` and the generator in the same change.
5. **Loading: real table if it exists, otherwise staging.** `seed load` (driven by `scripts/seed.sh`) connects as
   `<svc>_owner`. For each table:
   - **The migration exists** (`to_regclass('<svc>.<table>')`). In one transaction it:
     - checks every seeded column exists (fails naming the column, the phase and `Tables.X`);
     - runs `ALTER TABLE … NO FORCE ROW LEVEL SECURITY`;
     - runs `TRUNCATE … CASCADE` (with `--replace`; otherwise it refuses a non-empty table);
     - runs `COPY … FROM STDIN` through `CopyManager`;
     - counts the rows against the manifest;
     - runs `ALTER TABLE … FORCE ROW LEVEL SECURITY`;
     - commits, then runs `ANALYZE`.

     DDL is transactional, so no other session ever sees the table unforced, and any failure rolls everything
     back.
   - **Not created yet:** the same rows go into `seed_stage.<table>`. That schema:
     - is created by the owner, with `REVOKE ALL FROM PUBLIC`, so the runtime roles cannot reach it;
     - has its tables built from the planned columns with no constraints;
     - is dropped and recreated on every load.

     Timing and volumes are therefore real from P03 on, and the switch to the real table happens automatically
     the first time a phase's migration exists.
6. **Passwords hashed once.** Argon2id (m = 19456 KiB, t = 2, p = 1; MASTER_PLAN 7.1) in the exact encoding of
   spring-security-crypto's `Argon2PasswordEncoder`, computed with BouncyCastle.
   - Three demo hashes (`DEMO_PASSWORD_*`) plus **one** hash shared by every bulk account
     (`SEED_BULK_PASSWORD`). Hashing 100k accounts individually would take hours.
   - Salts come from the seeded stream, which keeps the output deterministic. That is acceptable for dev data
     only; identity always mints random salts.
   - `PasswordsTest` verifies the hashes with Spring's encoder.
7. **Fixtures for the trail** (`test-ui/fixtures/seed/*.json`, demo accounts only, API-shaped) are generated
   output.
   - `FixturesInSyncTest` fails when they are stale.
   - `./scripts/seed.sh fixtures` regenerates them.

## Consequences

- P04/P05/P07/P08/P09/P11 each owe one step: run `./scripts/seed.sh small` after the first migration and fix
  `Tables`/the generator if the loader reports a missing column. PlanAssert on the medium seed (P07+) loads into
  the real tables automatically.
- Owner-role loading needs `NO FORCE` inside the transaction, because FORCE makes RLS apply to the owner too.
  `ALTER TABLE` takes an ACCESS EXCLUSIVE lock for the duration of the load. That is fine for a dev/test seed and
  never used in production.
- `TRUNCATE … CASCADE` with `--replace` empties tables that reference a seeded table inside the same service
  database. Seeding is a reset, by design.
- Bulk accounts share a password hash; a login test against two bulk accounts proves nothing about salt
  uniqueness. Use identity's own tests for that.
- The medium dataset is ~430 MB of COPY files in `target/seed/` (not committed). Generation takes ~12 s and the
  load ~25 s on a dev box; the limit is 10 minutes (`SEED_MAX_SECONDS`).

## Alternatives rejected

- **Create the planned tables now from the seed.** This would race the real Flyway migrations (P04+), and
  `flyway.cleanDisabled` would leave stale shapes behind.
- **Load as the superuser.** MASTER_PLAN 11.1 says owner role. A superuser bypasses RLS silently, so a load would
  never exercise FORCE.
- **Per-row INSERTs through the app role.** 1.2 M expense rows through RLS context switches is minutes instead of
  seconds, and it requires the services that do not exist yet.
- **`java.util.Random`/`SplittableRandom` forks.** Their output is specified, but fork-by-label is not, and we
  need label-stable streams.
