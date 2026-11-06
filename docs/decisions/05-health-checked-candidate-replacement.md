# ADR 05: Health-check a candidate before replacing the active container

- Status: Accepted
- Date: 2026-11-06

## Context

Stopping the current container before proving the new revision works turns any
bad build, startup failure, or readiness failure into avoidable downtime. Docker
successfully starting a process does not mean the application can serve HTTP.

## Decision

Build the new image, allocate a host port, and run a deployment-specific
candidate container with memory, CPU, and PID limits. Keep the previous active
container running. Probe the candidate root URL and accept only HTTP 2xx or 3xx.
On success, store the candidate id and port as active, then remove the previous
container. On failure or cancellation, remove only the candidate.

## Consequences

- A failed revision does not destroy the known-good running container.
- Deployment state distinguishes image creation, process start, readiness, and promotion.
- Candidate names avoid collisions between attempts.
- For a short period two revisions may consume host resources.
- The active host port can change. Without a reverse proxy this is not stable-URL blue/green deployment.
- Port probing and later Docker binding are separate side effects; one worker reduces but does not eliminate the theoretical race.

## Alternatives considered

- Stop old then start new: simpler, but creates downtime and weak rollback behavior.
- Reuse the same container name/port: collides while the old revision remains active.
- Reverse-proxy blue/green switching: desired future direction once stable domains and routing enter scope.
