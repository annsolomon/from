# Runbook: Postgres roles in production (MASTER_PLAN 3.1)

Dev renders `infra/postgres/init/00-roles.sql.tmpl`. Production **never** uses that file or `.env` passwords.

For each service `<svc>`:
1. Create the 5 roles with `NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOINHERIT`, and never grant a row-security bypass.
   Generate passwords in the secret manager.
2. `CREATE DATABASE <svc> OWNER <svc>_owner`. Then `REVOKE ALL ON DATABASE <svc> FROM PUBLIC`, and grant `CONNECT` to the 5 roles.
3. Apply the per-role settings exactly as in the template: timeouts, `search_path`, and read-only for `_readonly`.
4. Inside the database: `REVOKE ALL ON SCHEMA public FROM PUBLIC`, grant `USAGE` to the 5 roles, and create the
   extensions (pgcrypto, citext, ltree, pg_trgm, btree_gist, pg_stat_statements) as the platform superuser.
5. Flyway connects as `<svc>_owner`. The service connects as `<svc>_app`, and the relay as `<svc>_relay`.
6. On Neon: roles are created through the console/API with the same attributes. PgBouncer runs in transaction mode.
