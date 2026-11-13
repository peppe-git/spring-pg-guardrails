# 8. Java 17 baseline; Spring Boot 3 and 4 from one jar

Status: accepted

## Context

Spring Boot 4 requires Java 17; Spring Boot 3 also targets 17. Nothing in this project needs Java 21 features.
Spring Boot 4 moved auto-configuration classes to new modules and packages.

## Decision

Compile with `--release 17`. Compile against Spring Boot 4.1 and test both 4.1 (default) and 3.5 (`-Dboot3`).
Reference Boot classes that moved only by name (`@AutoConfiguration(afterName = ...)`). Use only Spring Framework
APIs present in 6.1 and 7.0, and Hibernate APIs present in 6.6 and 7.

## Consequences

- One artifact per module.
- Boot 3 support can be dropped later by removing the profile, with a CHANGELOG note, if it becomes costly.
