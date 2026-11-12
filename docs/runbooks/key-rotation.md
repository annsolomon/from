# Key rotation (P19)

| Key | Where | Rotates | How |
|---|---|---|---|
| JWT signing keys (ES256) | `identity.signing_keys`, private key wrapped by the KEK | automatically every 30 days (`dexter.identity.keys.rotate-every`) | below, §1 |
| Identity KEK | Vault Transit `dexter-kek` (active `transit:dexter-kek`); local `k1` kept only to unwrap old rows | on schedule (yearly) or on suspicion | §2 |
| Per-account DEKs | `identity.account_keys`, wrapped by the KEK | never re-keyed in place; destroyed on account deletion (crypto-shred, P18) | — |
| Cursor signing keys | `dexter.cursor.keys.<kid>` per service (`*_CURSOR_KEY_C1`) | yearly | §3 |
| Service secrets (DB/Redis passwords, client secrets, webhook secrets, click-token key) | Vault KV `secret/dexter/<svc>` | on schedule / on suspicion | §4 |
| Vault tokens | per service, TTL 768 h locally; Agent-renewed in production | continuous | Agent |

## 1. JWT signing keys
Normal: nothing to do. The rotation job keeps an `active` and a pre-published `next` key; at rotation `next`
becomes `active`, the old one is `retired` and stays in the JWKS for `retain-retired` (2 h > access-token TTL) so
tokens it signed keep verifying (P04 `JwksRotationIT`; the trail showed it live).

Emergency (a private key may have leaked): rotate now, then shorten the overlap.
1. Set `DEXTER_IDENTITY_KEYS_ROTATE_EVERY=1m` on identity and roll it; the job rotates within a minute (watch the
   log line `SIGNING_KEY_ROTATED`), then set it back to `30d` and roll again.
2. Revoke sessions if tokens signed by the leaked key must stop working before they expire: the access-token TTL is
   10 minutes; refresh tokens are server-side (`identity.refresh_tokens`) and can be revoked per account.
3. The leaked key leaves the JWKS after `retain-retired`; to drop it at once delete the retired row
   (`DELETE FROM identity.signing_keys WHERE kid = '<kid>' AND status = 'retired'` as `identity_owner`).

## 2. The identity KEK
The KEK only wraps keys (DEKs, signing keys, admin TOTP secrets); rotating it never touches user data.

**Rotate the Transit key** (the normal case):
1. `vault write -f transit/keys/dexter-kek/rotate` — new wraps use the new version (`vault:v2:...`); old versions
   still decrypt.
2. The re-wrap job (`KekRewrapService`, every 10 min) only moves rows that are under a *different KEK id*; a new
   Transit *version* keeps the id `transit:dexter-kek`. To move old versions forward, run the job against the
   version: `vault read transit/keys/dexter-kek` → `min_available_version`; rows still on an old version are found
   by their prefix: `SELECT count(*) FROM identity.account_keys WHERE substring(dek_enc FROM 1 FOR 8) = 'vault:v1'`.
   Re-wrap them by switching the active id through a second key (step below) or leave them: Vault keeps old versions
   until `min_decryption_version` is raised.
3. Only when no row is on an old version: `vault write transit/keys/dexter-kek/config min_decryption_version=<n>`.

**Move to a new KEK id** (new Transit key, or local `k1` → Transit as done in P19):
1. Configure the new KEK and make it active (`IDENTITY_KEK_ACTIVE=transit:<new key>`; keep the old one configured).
   Grant identity's Vault policy encrypt/decrypt on the new key. Roll identity.
2. The re-wrap job moves every wrapped key to it, compare-and-set per row (`identity.kek_swap`), safe on all
   instances. Progress: `SELECT * FROM identity.kek_usage()` (as `identity_app` / `identity_owner`).
3. When `kek_usage()` shows nothing under the old id, remove it from the configuration (local) or disable its
   Transit key (`deletion_allowed` stays false until the backups that still hold old rows have expired — see
   restore.md).

Proof: `TransitKekIT` (rotation keeps old versions decrypting, wrong AAD fails), `KekRewrapIT` (rows move and still
open), `verify.sh full` → `full.vault-check` (every key under Transit after the seeded `k1` rows were re-wrapped).

## 3. Cursor signing keys
Cursors are short-lived (a client re-lists after a while). Add `dexter.cursor.keys.c2` (Vault KV of the service),
set `dexter.cursor.active-kid=c2`, roll; remove `c1` a day later. A cursor signed by a removed key answers
`400 CURSOR_INVALID` and the client starts the list again.

## 4. Service secrets (Vault KV)
1. Create the new credential next to the old one (Postgres: `ALTER ROLE <svc>_app PASSWORD '...'` only after the
   new value is in Vault; Redis: add the new password to the ACL user, keep the old; client secrets: identity
   accepts the configured secret only, so roll identity and the client in the same window).
2. `vault kv put secret/dexter/<svc> ...` (locally: change `.env`, `scripts/run.sh core` re-seeds Vault).
3. Roll the service (the Agent re-renders; locally `run.sh stop && run.sh services`).
4. Remove the old credential.
