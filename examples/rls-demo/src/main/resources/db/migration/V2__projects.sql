-- SPDX-License-Identifier: Apache-2.0
-- Tenant data: every table carries tenant_id and is protected by the library's policies.

create table projects (
    id bigint generated always as identity primary key,
    tenant_id uuid not null,
    name text not null,
    status varchar(16) not null check (status in ('OPEN', 'ARCHIVED')),
    created_at timestamptz not null
);
create index projects_tenant_idx on projects (tenant_id);
select rls.enable_tenant_rls('projects', 'tenant_id', true);

create table project_members (
    tenant_id uuid not null,
    project_id bigint not null references projects (id),
    member_email text not null,
    unique (project_id, member_email)
);
select rls.enable_tenant_rls('project_members');

-- Identity: read at login, before the tenant is known, so deliberately outside RLS (declared in the guards).
create table accounts (
    id bigint generated always as identity primary key,
    tenant_id uuid not null,
    email text not null unique
);
