-- SPDX-License-Identifier: Apache-2.0
--
-- Fail-closed row-level security helpers (spring-pg-guardrails / pg-rls-core).
--
-- Placeholders (Flyway syntax): rls_schema, tenant_guc, strict_guc, admin_read_guc.
-- Run it once, as the role that owns your tables (the migration role). It is idempotent.
--
-- Model
--   * The application writes the tenant into a transaction-local setting (GUC) at the start of every
--     transaction: select set_config('<tenant_guc>', '<tenant>', true).
--   * Every tenant table gets one policy per command. Reads compare the tenant column with
--     tenant_for_read(), writes with tenant_for_write().
--   * No tenant set => the functions return NULL => the comparison is never true => zero rows (fail-closed).
--     With strict mode on (<strict_guc> = 'on') they raise SQLSTATE RLS01 instead: the bug becomes an error.
--   * Optional admin read escape: only the FOR SELECT policy honours it, and only inside READ ONLY
--     transactions. INSERT, UPDATE and DELETE policies never do, so a cross-tenant DELETE cannot pass
--     through it (it can, with a single FOR ALL policy whose USING contains the escape: DELETE has no
--     WITH CHECK, so the USING alone decides which rows it may remove).

CREATE SCHEMA IF NOT EXISTS ${rls_schema};
GRANT USAGE ON SCHEMA ${rls_schema} TO PUBLIC;

-- The tenant for SELECT policies. NULL when unset (zero rows) or when the admin read escape is open (the
-- escape is a separate OR branch of the SELECT policy). Raises RLS01 when unset in strict mode.
CREATE OR REPLACE FUNCTION ${rls_schema}.tenant_for_read() RETURNS text
    LANGUAGE plpgsql STABLE PARALLEL SAFE
    SET search_path = pg_catalog, pg_temp
AS $fn$
DECLARE
    v text := current_setting('${tenant_guc}', true);
BEGIN
    IF v IS NOT NULL AND v <> '' THEN
        RETURN v;
    END IF;
    IF ${rls_schema}.admin_read_enabled() THEN
        RETURN NULL;
    END IF;
    IF current_setting('${strict_guc}', true) = 'on' THEN
        RAISE EXCEPTION 'row-level security: no tenant is set (${tenant_guc}) and strict mode is on'
            USING ERRCODE = 'RLS01',
                  HINT = 'Bind the tenant before the transaction starts, or set ${tenant_guc} with set_config(..., true).';
    END IF;
    RETURN NULL;
END
$fn$;

-- The tenant for INSERT, UPDATE and DELETE policies. Never widened by the admin read escape.
CREATE OR REPLACE FUNCTION ${rls_schema}.tenant_for_write() RETURNS text
    LANGUAGE plpgsql STABLE PARALLEL SAFE
    SET search_path = pg_catalog, pg_temp
AS $fn$
DECLARE
    v text := current_setting('${tenant_guc}', true);
BEGIN
    IF v IS NOT NULL AND v <> '' THEN
        RETURN v;
    END IF;
    IF current_setting('${strict_guc}', true) = 'on' THEN
        RAISE EXCEPTION 'row-level security: no tenant is set (${tenant_guc}) for a write, and strict mode is on'
            USING ERRCODE = 'RLS01',
                  HINT = 'The admin read escape never allows writes: write as the tenant that owns the row.';
    END IF;
    RETURN NULL;
END
$fn$;

-- The admin read escape is open: the GUC says so AND the transaction is READ ONLY.
CREATE OR REPLACE FUNCTION ${rls_schema}.admin_read_enabled() RETURNS boolean
    LANGUAGE sql STABLE PARALLEL SAFE
    SET search_path = pg_catalog, pg_temp
AS $fn$
    SELECT coalesce(current_setting('${admin_read_guc}', true), '') = 'on'
       AND current_setting('transaction_read_only') = 'on'
$fn$;

-- Protects one table (and, for a partitioned table, every partition that exists now):
-- ENABLE + FORCE row level security and four policies named rls_tenant_select/insert/update/delete.
-- Re-running it replaces those four policies; it does not touch other policies on the table, which are
-- PERMISSIVE and therefore OR-ed with these: drop legacy policies in the same migration.
CREATE OR REPLACE FUNCTION ${rls_schema}.enable_tenant_rls(
        target regclass,
        tenant_column name DEFAULT 'tenant_id',
        admin_read boolean DEFAULT false)
    RETURNS void
    LANGUAGE plpgsql
    SET search_path = pg_catalog, pg_temp
AS $fn$
DECLARE
    rel regclass;
    col_type text;
    read_check text;
    write_check text;
BEGIN
    -- pg_partition_tree() returns no rows for a table that is not partitioned: include the target explicitly.
    FOR rel IN SELECT target UNION SELECT relid FROM pg_partition_tree(target) LOOP
        SELECT format_type(a.atttypid, a.atttypmod) INTO col_type
          FROM pg_attribute a
         WHERE a.attrelid = rel AND a.attname = tenant_column AND a.attnum > 0 AND NOT a.attisdropped;
        IF col_type IS NULL THEN
            RAISE EXCEPTION 'table % has no column %', rel, tenant_column;
        END IF;

        read_check := format('%I = (SELECT %I.tenant_for_read()::%s)', tenant_column, '${rls_schema}', col_type);
        write_check := format('%I = (SELECT %I.tenant_for_write()::%s)', tenant_column, '${rls_schema}', col_type);

        EXECUTE format('ALTER TABLE %s ENABLE ROW LEVEL SECURITY', rel);
        EXECUTE format('ALTER TABLE %s FORCE ROW LEVEL SECURITY', rel);
        EXECUTE format('DROP POLICY IF EXISTS rls_tenant_select ON %s', rel);
        EXECUTE format('DROP POLICY IF EXISTS rls_tenant_insert ON %s', rel);
        EXECUTE format('DROP POLICY IF EXISTS rls_tenant_update ON %s', rel);
        EXECUTE format('DROP POLICY IF EXISTS rls_tenant_delete ON %s', rel);

        EXECUTE format('CREATE POLICY rls_tenant_select ON %s FOR SELECT USING (%s%s)', rel, read_check,
                       CASE WHEN admin_read
                            THEN format(' OR (SELECT %I.admin_read_enabled())', '${rls_schema}')
                            ELSE '' END);
        EXECUTE format('CREATE POLICY rls_tenant_insert ON %s FOR INSERT WITH CHECK (%s)', rel, write_check);
        EXECUTE format('CREATE POLICY rls_tenant_update ON %s FOR UPDATE USING (%s) WITH CHECK (%s)', rel,
                       write_check, write_check);
        EXECUTE format('CREATE POLICY rls_tenant_delete ON %s FOR DELETE USING (%s)', rel, write_check);
    END LOOP;
END
$fn$;

GRANT EXECUTE ON FUNCTION ${rls_schema}.tenant_for_read() TO PUBLIC;
GRANT EXECUTE ON FUNCTION ${rls_schema}.tenant_for_write() TO PUBLIC;
GRANT EXECUTE ON FUNCTION ${rls_schema}.admin_read_enabled() TO PUBLIC;
REVOKE ALL ON FUNCTION ${rls_schema}.enable_tenant_rls(regclass, name, boolean) FROM PUBLIC;
