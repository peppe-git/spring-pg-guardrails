-- SPDX-License-Identifier: Apache-2.0
-- A hand-written migration with the two classic mistakes. Applied by BrokenRlsIsCaughtTest only.
create schema billing;

create table billing.invoices (
    id bigint generated always as identity primary key,
    tenant_id uuid not null,
    number text not null,
    total numeric(12, 2) not null
);
alter table billing.invoices enable row level security;   -- mistake 1: no FORCE
create policy invoices_isolation on billing.invoices        -- mistake 2: the admin escape inside FOR ALL USING
    using (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid
           or current_setting('app.rls_admin_read', true) = 'on')
    with check (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

create table billing.payments (                             -- mistake 3: forgot RLS entirely
    id bigint generated always as identity primary key,
    tenant_id uuid not null,
    invoice_id bigint not null references billing.invoices (id),
    amount numeric(12, 2) not null
);
