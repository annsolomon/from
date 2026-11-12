# ADR-0026: Vault — per-service secrets from KV, the identity KEK in Transit; Consul dropped

- Status: Accepted — proven by CI run 43309873447 (git `4eef6f3`): `full.vault-check` 6/6, `TransitKekIT`, `KekRewrapIT` (BUILD_README §3).
- Context: MASTER_PLAN 7.4 / P19 step 2: "Vault: KV for secrets + Transit for the KEK. The Consul decision is
  recorded in an ADR (likely dropped — compose/K8s DNS is enough)." Until P19 every service process got the whole
  `.env` (every service's database passwords, the identity KEK, the commerce click key, ...), and the identity KEK
  was a 32-byte value in the identity process.

## Decisions

1. **Vault in the core profile.** `hashicorp/vault:2.0` as a single server with file storage in a compose volume
   (A-122: dev mode would lose the Transit key on restart while Postgres kept what it wrapped), initialised and
   unsealed by `scripts/run.sh core` (`scripts/lib/vault_tool.py init`; one key share in `.run/vault/init.json`):
   the Transit engine and key `dexter-kek` (aes256-gcm96); `secret/dexter/<svc>` (KV v2) holding exactly the
   secrets that service's `application.yml` references; one ACL policy per service (`read` on its own path;
   identity also `update` on `transit/encrypt|decrypt/dexter-kek`) and one token per service.
2. **Secrets reach a service the way Vault Agent would deliver them.** The launcher starts each service with
   `env -u <every secret in .env> <its own secrets read from Vault with its own token> java ...`. The services
   keep reading `${PG_PW_APP}`-style placeholders and do not link a Vault client for KV: in Kubernetes the Agent
   injector (Kubernetes auth, one role per service account) renders the same variables/files, so the code and the
   configuration do not change between laptop and cluster. Spring Cloud Vault was considered and not taken: a
   second configuration source in every service, a hard start-up dependency on Vault for every service, and nothing
   gained over the Agent. `DEXTER_VAULT=off` keeps the old `.env` launch for a quick local run.
3. **The identity KEK lives in Transit.** `KekRing` holds local KEKs (`k1`, ...) and at most one remote KEK
   `transit:<key>`; `TransitKek` calls `transit/encrypt|decrypt/<key>` with the same associated data as the local
   AES-GCM (`dek:<account>`, `signing-key:<kid>`, `admin-totp:<admin>`), so a wrapped key copied to another row does
   not open — proven against a real Vault. The stored value is Vault's `vault:v<n>:...` string; `kek_id` records
   `transit:dexter-kek`. With Vault up the launcher sets `IDENTITY_KEK_ACTIVE=transit:dexter-kek`; `k1` stays
   configured for unwrapping what it wrapped before. The token is read per call (fixed, or a `VAULT_TOKEN_FILE`
   Agent sink re-read on every call). Identity is the only process holding a Vault token at run time.
4. **Re-wrap job.** Transit's own `rewrap` does not take associated data, and a local KEK cannot be retired while
   rows still name it, so identity re-wraps in-process: `identity.kek_stale` lists wrapped keys not under the active
   KEK, the service unwraps and wraps (outside any transaction), `identity.kek_swap` replaces each row only if it is
   still under the KEK it read (compare-and-set: safe on every instance, concurrent shreds win). Scheduled every
   10 min (`dexter.identity.kek.rewrap-enabled`); `identity.kek_usage()` shows progress. Rotation =
   `transit/keys/dexter-kek/rotate` (new versions) or a new active KEK, then the job, then `min_decryption_version`
   / removing the old local key (docs/runbooks/key-rotation.md).
5. **Consul: dropped.** Service discovery is DNS (compose service names; Kubernetes Services), configuration is
   files + environment, health is the orchestrator's probes, and secrets are Vault. Consul would add a cluster to
   run and nothing the stack is missing; Vault uses integrated storage (Raft) in production, not Consul.
6. **Licence.** Vault ≥ 1.15 is BSL 1.1: self-hosting for our own product is permitted; OpenBao (MPL, API
   compatible for KV v2 and Transit) is the drop-in if that changes — nothing here uses an Enterprise feature.

## Proof

- `TransitKekIT` (real Vault, identity's own policy): wrap/unwrap, wrong associated data fails, local-KEK data
  still opens after the move, a ring without Vault refuses `transit:` ids with a clear error, rotation keeps old
  versions decrypting, the token cannot use another key, read KV or rotate; signing keys wrapped in Transit sign.
- `KekRewrapIT` (real schema): rows under a retiring KEK move to the active one in batches, still open under the
  same associated data, the swap is compare-and-set, an unopenable row is reported and never changed.
- `verify.sh full` → `full.vault-check`: every token reads its own secrets and gets 403 on another service's; only
  identity can use the Transit key; every running service's environment (`/proc/<pid>/environ`) holds its own
  secrets and no other service's (and no Vault token outside identity); every identity DEK, signing key and admin
  TOTP secret is under `transit:dexter-kek`, stored as Vault ciphertext (rows under `k1` would be re-wrapped by
  the job, every 30 s locally; `KekRewrapIT` proves the move).

## Consequences

- Identity cannot start without Vault once anything is wrapped under Transit (it must unwrap its signing key):
  Vault is a tier-0 dependency of sign-in — HA Raft cluster in production, and the alerting covers it.
- Locally Vault's data lives in the `vaultdata` volume next to `pgdata`; `run.sh nuke` resets both and
  `.run/vault`. Losing `.run/vault/init.json` while the volume stays means a sealed Vault: `run.sh nuke`.
