-- MASTER_PLAN 3.6: range partitions by created_at, created ahead and dropped by age.
-- SECURITY DEFINER so {{schema}}_relay can manage partitions without owning tables. Inputs are
-- validated identifiers only; all DDL uses format(%I/%L). Callers hold an advisory lock
-- (in.dexter.db.partition.PartitionManager) so only one instance runs them at a time.
-- Partition naming: <table>_pYYYYMMDD (day) or <table>_pYYYYMM (month), bounds in UTC.

CREATE OR REPLACE FUNCTION {{schema}}.create_partitions_ahead(
    p_table text, p_grain text, p_ahead integer, p_anchor timestamptz DEFAULT now())
RETURNS integer
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS
$$
DECLARE
  v_start timestamp;
  v_lo    timestamp;
  v_hi    timestamp;
  v_name  text;
  v_made  integer := 0;
BEGIN
  IF p_table !~ '^[a-z_][a-z0-9_]{0,40}$' THEN RAISE EXCEPTION 'bad table name %', p_table; END IF;
  IF p_grain NOT IN ('day', 'month') THEN RAISE EXCEPTION 'bad grain %', p_grain; END IF;
  IF p_ahead < 0 OR p_ahead > 400 THEN RAISE EXCEPTION 'bad ahead %', p_ahead; END IF;
  IF to_regclass(format('%I.%I', '{{schema}}', p_table)) IS NULL THEN
    RAISE EXCEPTION 'no table {{schema}}.%', p_table;
  END IF;
  v_start := date_trunc(p_grain, p_anchor AT TIME ZONE 'UTC');
  FOR i IN 0..p_ahead LOOP
    v_lo := v_start + make_interval(days => CASE WHEN p_grain = 'day' THEN i ELSE 0 END,
                                    months => CASE WHEN p_grain = 'month' THEN i ELSE 0 END);
    v_hi := v_lo + CASE WHEN p_grain = 'day' THEN interval '1 day' ELSE interval '1 month' END;
    v_name := p_table || '_p' || to_char(v_lo, CASE WHEN p_grain = 'day' THEN 'YYYYMMDD' ELSE 'YYYYMM' END);
    IF to_regclass(format('%I.%I', '{{schema}}', v_name)) IS NULL THEN
      EXECUTE format('CREATE TABLE %I.%I PARTITION OF %I.%I FOR VALUES FROM (%L) TO (%L)',
                     '{{schema}}', v_name, '{{schema}}', p_table,
                     (v_lo AT TIME ZONE 'UTC'), (v_hi AT TIME ZONE 'UTC'));
      v_made := v_made + 1;
    END IF;
  END LOOP;
  RETURN v_made;
END
$$;

-- Drops partitions whose upper bound is <= p_cutoff. With p_guard_column set, a partition is only
-- dropped when no row has that column NULL (outbox: published_at), so nothing unpublished is lost.
CREATE OR REPLACE FUNCTION {{schema}}.drop_partitions_older_than(
    p_table text, p_cutoff timestamptz, p_guard_column text DEFAULT NULL)
RETURNS integer
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS
$$
DECLARE
  r        record;
  v_suffix text;
  v_lo     timestamp;
  v_hi     timestamp;
  v_busy   boolean;
  v_dropped integer := 0;
BEGIN
  IF p_table !~ '^[a-z_][a-z0-9_]{0,40}$' THEN RAISE EXCEPTION 'bad table name %', p_table; END IF;
  IF p_guard_column IS NOT NULL AND p_guard_column !~ '^[a-z_][a-z0-9_]{0,62}$' THEN
    RAISE EXCEPTION 'bad guard column %', p_guard_column;
  END IF;
  FOR r IN
    SELECT c.relname
    FROM pg_inherits i
    JOIN pg_class c ON c.oid = i.inhrelid
    JOIN pg_class p ON p.oid = i.inhparent
    JOIN pg_namespace n ON n.oid = p.relnamespace
    WHERE n.nspname = '{{schema}}' AND p.relname = p_table
    ORDER BY c.relname
  LOOP
    v_suffix := substring(r.relname FROM '_p([0-9]{6,8})$');
    CONTINUE WHEN v_suffix IS NULL;
    IF length(v_suffix) = 8 THEN
      v_lo := to_timestamp(v_suffix, 'YYYYMMDD')::timestamp; v_hi := v_lo + interval '1 day';
    ELSE
      v_lo := to_timestamp(v_suffix || '01', 'YYYYMMDD')::timestamp; v_hi := v_lo + interval '1 month';
    END IF;
    CONTINUE WHEN (v_hi AT TIME ZONE 'UTC') > p_cutoff;
    IF p_guard_column IS NOT NULL THEN
      EXECUTE format('SELECT EXISTS (SELECT 1 FROM %I.%I WHERE %I IS NULL)', '{{schema}}', r.relname, p_guard_column)
        INTO v_busy;
      CONTINUE WHEN v_busy;
    END IF;
    EXECUTE format('DROP TABLE %I.%I', '{{schema}}', r.relname);
    v_dropped := v_dropped + 1;
  END LOOP;
  RETURN v_dropped;
END
$$;

REVOKE ALL ON FUNCTION {{schema}}.create_partitions_ahead(text, text, integer, timestamptz) FROM PUBLIC;
REVOKE ALL ON FUNCTION {{schema}}.drop_partitions_older_than(text, timestamptz, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION {{schema}}.create_partitions_ahead(text, text, integer, timestamptz) TO {{schema}}_relay;
GRANT EXECUTE ON FUNCTION {{schema}}.drop_partitions_older_than(text, timestamptz, text) TO {{schema}}_relay;
