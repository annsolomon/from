# Runbook: Redis ACL users (ADR-0004 §8)

- Template: `infra/redis/acl.tmpl` (one rule line for every service). Rendered by `infra/redis/render-acl.sh` inside the
  Redis container at every start into `/tmp/users.acl` (never into the data volume); Redis starts with `--aclfile`.
- Users: `default` (password `REDIS_PASSWORD`, all permissions; ops and the healthcheck only) plus one user per name in
  `DEXTER_REDIS_USERS`, password `REDIS_PW_<SERVICE>` or else `REDIS_PW_SERVICE`. Allowed characters: `[A-Za-z0-9_.@%+=-]`.
- A service user may touch only `<svc>:*`, `gen:<svc>:*`, `lock:<svc>:*` and channel `cache:inv:<svc>`, with the commands
  dexter-cache needs. Services configure `spring.data.redis.username=<svc>`, `password`, `timeout=200ms`.
- Inspect: `set -a; . ./.env; set +a; sh infra/redis/render-acl.sh`; live: `docker compose ... exec redis redis-cli -a "$REDIS_PASSWORD" ACL LIST`.
- Change a password or the rule: edit `.env` / the template and recreate the container (`./scripts/run.sh core`); no data migration.
- Adding a command to the template: add it, then run `RedisAclIT` (dexter-test) — it applies the same line with `ACL SETUSER`
  and proves every cache operation still works with zero bypasses.
- `verify.sh full` → `full.infra-checks` checks the user count and that the ledger user is refused foreign keys,
  `KEYS`, `FLUSHALL`, `CONFIG GET` and `DEL`.
- Worst-case cache residency after a privacy delete: the longest tenant TTL (30 min) if the purge step itself failed.
