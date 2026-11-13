# Security policy

This project touches tenant isolation, so a defect can expose one customer's data to another. Please report
suspected vulnerabilities privately.

## Reporting

Use GitHub's **private vulnerability reporting** on this repository ("Security" tab, "Report a vulnerability").
Please include the version or commit, PostgreSQL and Spring versions, and the smallest schema and code that shows
the problem. Do not open a public issue.

You can expect an acknowledgement within 7 days and an assessment within 30 days. Fixes are released as soon as
they are ready; the reporter is acknowledged in the release notes unless anonymity is requested.

## Scope

In scope: ways to read or write another tenant's rows through the runtime, the SQL templates or the policies they
generate; guards that report green on a schema or code base that violates the property they check.

Out of scope, by design (see [docs/threat-model.md](docs/threat-model.md)): SQL injection in the application, roles
that bypass RLS by configuration (superuser, `BYPASSRLS`, owners of non-forced tables), side channels such as global
unique constraints, and data outside PostgreSQL.

## Supported versions

Until 1.0, only the latest release receives fixes.
