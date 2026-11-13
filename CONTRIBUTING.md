# Contributing

Thank you for considering a contribution. The project is maintained in limited spare time, so small, focused
changes are the ones most likely to be merged quickly.

## Before you start

- For anything larger than a fix, open an issue first and describe the defect the change prevents. Every rule in
  this repository exists because of a real failure; a new rule needs one too.
- Security problems: do **not** open an issue, see [SECURITY.md](SECURITY.md).

## Building

```bash
./mvnw verify            # Spring Boot 4, needs Docker for Testcontainers
./mvnw verify -Dboot3    # Spring Boot 3
scripts/scrub-check.sh
```

Use the Maven wrapper; run one build at a time per checkout.

### The scrub check

`scripts/scrub-check.sh` fails on material that must not be published: secrets and keys, e-mail addresses, money
amounts, dates, internal id formats. CI runs it with these generic rules only.

Maintainers can add **private** patterns that must never appear in the repository, not even inside the script.
They live in a file outside the checkout, named by an environment variable:

```bash
SCRUB_PRIVATE_PATTERNS=/path/outside/the/checkout/private-patterns.txt scripts/scrub-check.sh
```

The file holds one extended regular expression per line, matched case-insensitively; empty lines and lines
starting with `#` are ignored. For example:

```text
# names of internal systems
\binternal-billing\b
legacy[-_ ]?portal
```

Without the variable the script warns on stderr and runs the generic rules; with the variable pointing to a missing
file, or to a file with an invalid expression, it exits with code 2. Never commit the private file.

Maintainers release as described in [RELEASING.md](RELEASING.md).

## Rules for changes

- **Every guard must be seen failing.** A new rule comes with a fixture it reports and a test asserting the exact
  findings; a changed recogniser comes with the case that used to slip through.
- A rule declares a floor and accepts exemptions only with a reason (see `docs/adr/0007-guard-engine.md`).
- Schema questions are answered from the PostgreSQL catalog, not by parsing migrations.
- No new runtime dependency in `pg-rls-core` without discussion; test modules keep optional dependencies optional.
- Java 17 language level; code must work with Spring Boot 3.5 and 4.x.
- Every source file starts with `SPDX-License-Identifier: Apache-2.0`.
- Commit messages: `type(scope): sentence in English` (`feat`, `fix`, `docs`, `test`, `chore`, `refactor`).
- Update `CHANGELOG.md` under *Unreleased*.

By contributing you agree that your contribution is licensed under the Apache License 2.0.
