-- SPDX-License-Identifier: Apache-2.0
--
-- The role the application connects as at runtime (placeholders: runtime_role, rls_schema).
--
-- Row-level security only protects you if this role is subject to it:
--   NOSUPERUSER   superusers bypass every policy, FORCEd or not;
--   NOBYPASSRLS   the attribute literally means "skip all policies";
--   not an owner  owners skip policies that are not FORCEd (the library always FORCEs, but do not rely on it);
--   no membership in a superuser/BYPASSRLS role (anything running as this role could SET ROLE to it).
-- Migrations (DDL) run as a different role, the owner of the tables.
--
-- Run it as a role with CREATEROLE (typically in the database bootstrap, not in a Flyway migration).
-- The password is set by your infrastructure, never in a file under version control:
--   ALTER ROLE <runtime role> PASSWORD '...';
-- Optional defence in depth: make strict mode the default for every session of this role, independently of
-- the application:  ALTER ROLE <runtime role> SET app.rls_strict = 'on';

DO $do$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '${runtime_role}') THEN
        CREATE ROLE ${runtime_role} LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE NOREPLICATION;
    END IF;
END
$do$;

-- Re-assert the attributes on every run: a role that already existed may have drifted.
ALTER ROLE ${runtime_role} NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE NOREPLICATION;

-- The helpers' schema may not exist yet when this runs before the migrations: create it empty.
CREATE SCHEMA IF NOT EXISTS ${rls_schema};

GRANT USAGE ON SCHEMA public TO ${runtime_role};
GRANT USAGE ON SCHEMA ${rls_schema} TO ${runtime_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO ${runtime_role};
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO ${runtime_role};

-- Tables and sequences created later by the role running this script (your migration role).
-- If migrations run as another role, add FOR ROLE <migration role> to both statements.
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO ${runtime_role};
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO ${runtime_role};
