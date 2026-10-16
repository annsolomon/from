-- MASTER_PLAN 3.2: schema, grants and the RLS context functions. Runs as {{schema}}_owner.
CREATE SCHEMA IF NOT EXISTS {{schema}} AUTHORIZATION {{schema}}_owner;
REVOKE ALL ON SCHEMA {{schema}} FROM PUBLIC;
GRANT USAGE ON SCHEMA {{schema}} TO {{schema}}_app, {{schema}}_admin, {{schema}}_relay, {{schema}}_readonly;

-- NULL when unset or cleared, so every tenant policy matches zero rows (fails closed).
CREATE OR REPLACE FUNCTION {{schema}}.app_account() RETURNS uuid
LANGUAGE sql STABLE PARALLEL SAFE AS
$$ SELECT NULLIF(current_setting('app.account_id', true), '')::uuid $$;

CREATE OR REPLACE FUNCTION {{schema}}.app_actor_role() RETURNS text
LANGUAGE sql STABLE PARALLEL SAFE AS
$$ SELECT NULLIF(current_setting('app.actor_role', true), '') $$;

REVOKE ALL ON FUNCTION {{schema}}.app_account() FROM PUBLIC;
REVOKE ALL ON FUNCTION {{schema}}.app_actor_role() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION {{schema}}.app_account(), {{schema}}.app_actor_role()
  TO {{schema}}_app, {{schema}}_admin, {{schema}}_relay, {{schema}}_readonly;
