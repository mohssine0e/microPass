# ADR 03: Use PostgreSQL with Flyway as the production-like persistence path

- Status: Accepted
- Date: 2026-11-06

## Context

H2 made the prototype easy to start, but an embedded database can hide SQL,
type, locking, and schema behavior that differs from the intended server
database. Hibernate `ddl-auto=update` also changes schemas implicitly and does
not provide a reviewable history.

## Decision

Keep H2 as the zero-setup default for exploration. Add a `postgres` profile that
uses PostgreSQL, enables Flyway, and sets Hibernate to `validate`. Store the
initial schema in `V1__create_micropass_schema.sql`. Run repository integration
tests against PostgreSQL 16 through Testcontainers and the same migration.

## Consequences

- Schema changes are ordered, versioned, reviewable, and repeatable.
- CI detects PostgreSQL-specific mapping or migration failures.
- Production-like startup fails early when entities and schema disagree.
- Local production-like work now requires PostgreSQL; Compose provides it.
- H2 remains useful but is not evidence that PostgreSQL behavior is correct.
- Future schema changes must be added as new migrations rather than editing an already-applied version.

## Alternatives considered

- H2 only: simplest, but weak evidence for the target database.
- Hibernate `ddl-auto=update` in all environments: convenient but implicit and unsafe for controlled evolution.
- Liquibase: capable alternative; Flyway was chosen for the small SQL-first migration set.
