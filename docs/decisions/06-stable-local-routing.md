# ADR 06 — Optional stable local routing with Caddy

## Status

Implemented as an optional single-host feature.

## Context

Candidate containers need separate host ports so the old version can continue
serving while the new version is checked. A direct `localhost:<port>` link can
therefore change after every successful deployment.

## Decision

An optional Caddy container listens on port `9080`. Each app has a host-based
URL of `http://app-<id>.localhost:9080/`. The app ID produces a valid, unique
host label even when names contain underscores or are reused. Caddy forwards
requests to the app's current Docker host port through `host.docker.internal`.

microPass posts a complete Caddyfile to the local admin API. On startup it
rebuilds routes from active app records. During deployment, it waits for the
candidate readiness probe, then loads the new route before changing the app's
active container record and retiring the old container. If Caddy rejects the
update, the candidate fails and the previous route and container remain. Route
updates are serialized in one JVM, and Caddy applies each config load
atomically. Caddy saves the active config and resumes it after its own restart.

Stop and delete remove an app's route. Direct host ports remain available for
readiness probes and for installations that do not enable Caddy.

## Limits

- `.localhost` routes work for browsers on the same machine. Remote users need
  DNS and a separately designed hostname policy.
- This is plain HTTP with no identity boundary. It is not a public ingress.
- Caddy and the database do not share a transaction. A process crash between
  route load and database save can leave an inconsistent route until microPass
  restarts and reconciles from the database.
- In-flight requests on the retired container may end when it is removed.
  Connection draining is a separate improvement.
