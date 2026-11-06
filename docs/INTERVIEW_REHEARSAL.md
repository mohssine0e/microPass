# microPass Interview Rehearsal Guide

This guide helps you explain the project as an engineer instead of reciting a
technology list. Adapt the wording to your own voice; do not memorize it word
for word.

## The rule for every strong answer

Use five parts:

1. **Context:** What problem or constraint existed?
2. **Decision:** What did you choose and why?
3. **Mechanism:** How does it work technically?
4. **Trade-off:** What cost or limitation remains?
5. **Evidence:** What test, behavior, or measurement supports the claim?

If you name a technology, be ready to explain the responsibility it owns, one
alternative, and one failure mode.

## 30-second project explanation

> microPass is a trusted single-node platform that deploys Git repositories as
> Docker containers. A Spring Boot API records a deployment, RabbitMQ hands it
> durably to one worker, and the worker synchronizes Git, builds an image, starts
> a resource-limited candidate, and promotes it only after HTTP readiness. React
> receives live logs through SSE. PostgreSQL and Flyway persist reproducible
> state, and Testcontainers verifies the real database path.

## 90-second project explanation

> I built microPass to learn the full path from a user request to an operated
> application without beginning with Kubernetes. The user registers a Git
> repository and requests deployment. The API validates the request, writes a
> `QUEUED` deployment, publishes an event to RabbitMQ, and returns `202`.
>
> The RabbitMQ listener performs the pipeline before returning, so ACK follows
> handling. Because delivery is at least once, an atomic `QUEUED`-to-`CLONING`
> update makes duplicate delivery safe. Git and Docker subprocesses have real
> timeouts, concurrent output draining, cancellation, and process-tree cleanup.
> The new revision runs as a candidate with CPU, memory, and PID limits. Only an
> HTTP 2xx or 3xx promotes it, so a failed candidate leaves the old container
> running. Logs stream through SSE and end with an explicit terminal event.
>
> The main deliberate limitations are one trusted host, no authentication or
> reverse proxy, and a database/RabbitMQ dual write. The next reliability step
> would be an outbox; stable domains would require a proxy routing layer.

## Five-minute technical structure

1. User problem and current single-node scope
2. Request-to-container flow
3. RabbitMQ ACK and idempotency
4. Process cancellation and safe container promotion
5. PostgreSQL/Flyway and SSE
6. Tests and CI evidence
7. Security boundary and next improvements

Use [DEMO_SCRIPT.md](DEMO_SCRIPT.md) for the live version.

## Questions and model answers

### 1. What problem does microPass solve?

It automates the repetitive path from Git source to a running, checked
container: source synchronization, image build, host-port selection, runtime
limits, readiness, state tracking, logs, cancellation, and stop/delete actions.
Its current user is a trusted developer or small team operating one machine.

**Follow-up:** Why not just use a shell script?

A script can execute commands, but a platform also needs durable state,
concurrent request rules, job delivery, cancellation, observable progress,
recovery, an API, and a user interface. A script could still be used inside a
platform, but it would not remove those coordination responsibilities.

### 2. Walk me through one deployment.

The API validates the app id and checks for active work. It inserts a `QUEUED`
deployment, publishes an event, and returns `202`. The worker atomically claims
the deployment, synchronizes the chosen Git branch, records the commit, builds
an image, allocates a free host port, runs a unique candidate container, and
probes readiness. Success promotes the candidate and retires the old container;
failure removes only the candidate. State and logs are persisted and the SSE
stream receives a terminal event.

### 3. Why return HTTP 202?

The server accepted work but has not completed it. Returning `200` with an
implied final success would confuse queue acceptance with deployment success.
The deployment resource represents the asynchronous result.

### 4. Why RabbitMQ? Why not only a virtual thread?

A virtual thread is an inexpensive JVM execution context for blocking work. It
does not persist a job when the process dies, buffer work independently, or
control broker delivery and acknowledgement. RabbitMQ owns durable hand-off and
backpressure; a thread still executes the delivered work.

### 5. When is the RabbitMQ message acknowledged?

Spring's listener acknowledges after the listener method returns successfully.
The worker calls the pipeline inside that method rather than starting detached
work, so the message remains unacknowledged while handling runs.

**Caution:** Do not say this creates exactly-once execution.

### 6. What does at-least-once delivery mean here?

The same event may arrive more than once—for example, if work completes but the
consumer connection fails before RabbitMQ receives the ACK. microPass uses an
atomic conditional update that only changes `QUEUED` to `CLONING`. One delivery
claims the job; later deliveries see zero updated rows and stop.

### 7. What are prefetch and consumer concurrency?

Consumer concurrency is how many listener threads process messages. Prefetch is
how many unacknowledged messages a consumer may reserve ahead. Both are one in
v0.1 so expensive builds are serialized and one worker does not reserve a long
backlog it cannot process promptly.

### 8. What is the dead-letter queue for?

It receives messages rejected or dead-lettered from the deployment queue, such
as a poison message that the listener cannot handle. A normal application build
failure is a valid business outcome: microPass records `FAILED` and returns from
the listener, so that message is normally ACKed instead of dead-lettered.

### 9. What is the database/RabbitMQ dual-write problem?

The deployment row and RabbitMQ event are written to independent systems. No
single local transaction covers both. A sudden crash after the row commits but
before publication can leave queued state without a message. A transactional
outbox would store the event beside the deployment in one transaction and let a
separate relay publish it reliably.

### 10. Why PostgreSQL when H2 already worked?

H2 is excellent for quick local work but can differ in SQL, types, locking, and
DDL behavior. PostgreSQL is the production-like target. Testcontainers starts a
real PostgreSQL instance, applies the same Flyway migration, and verifies entity
persistence and relationships.

### 11. What does Flyway add?

Flyway turns schema evolution into ordered versioned SQL files and records which
ones were applied. Hibernate then validates the mapping. This is safer and more
reviewable than letting `ddl-auto=update` silently decide production changes.

### 12. Why can’t `@Transactional` roll back a Docker container?

A database transaction controls database connections and records. `docker run`
changes an independent daemon. PostgreSQL cannot undo that external side effect.
The application must compensate—for example, remove a failed candidate—and may
later reconcile actual Docker state with database state.

### 13. Image versus container?

An image is an immutable packaged filesystem and configuration template. A
container is a running process instance created from that image. Building an
image proves packaging; starting a container proves process creation; neither
alone proves the application is ready for traffic.

### 14. Readiness versus liveness?

Readiness decides whether an instance can receive traffic now. Liveness decides
whether it is functioning enough to continue or should be restarted. microPass
v0.1 performs a readiness check during deployment; it does not continuously run
a separate liveness controller.

### 15. How does safe replacement work?

The old container stays active while a uniquely named candidate starts on a
free port. microPass accepts only HTTP 2xx/3xx. On success it stores the candidate
as active and removes the old container. On failure it removes the candidate.

**Caution:** Call it safe replacement, not fixed-endpoint zero downtime. There
is no reverse proxy switching one stable domain or port.

### 16. Is host-port allocation race-free?

No. microPass checks database assignments and briefly binds a socket to test
availability, then releases it before Docker binds. Another process could take
the port in that gap. Serial worker execution reduces the chance. A stronger
design would let a routing layer or Docker allocate endpoints and then publish
the result.

### 17. How does cancellation work?

The service stores a cancellation flag and the active `Process` reference. A
cancel request sets the flag, kills the process and descendants, persists
`CANCELLED`, and completes the SSE stream. Pipeline checkpoints also test the
flag between stages. Killing descendants matters because Git can start helper
processes that would survive if only the parent died.

### 18. Why drain subprocess output concurrently?

Operating systems give child processes bounded stdout/stderr pipes. If the child
fills a pipe while the parent waits without reading, both can deadlock. A reader
virtual thread drains merged output while the calling thread enforces timeout
and cancellation.

### 19. Why SSE instead of WebSockets?

Logs are mainly server-to-browser text. SSE uses normal HTTP, has native browser
`EventSource`, and is simpler for one-way streams. WebSockets would become more
attractive for frequent bidirectional commands, binary data, or a multiplexed
interactive protocol.

### 20. Why send a named complete SSE event?

`EventSource` automatically reconnects after a disconnected stream. A normal
close alone can cause repeated reconnection and historical log replay. The
explicit final event carries terminal status and tells React to close deliberately.

### 21. What test types are present?

- Unit tests cover timeout, cancellation, readiness status rules, duplicate claim,
  lifecycle behavior, and Dockerfile boundaries.
- A PostgreSQL Testcontainers integration test runs Flyway and real repository operations.
- The React production build performs TypeScript checking and bundling.
- CI repeats backend verification, frontend build, and JAR packaging.

A future end-to-end test should start RabbitMQ, PostgreSQL, the backend, and a
small fixture deployment, but it should remain a small critical-path suite.

### 22. What is the security boundary?

microPass assumes a trusted operator and trusted repository. Dockerfile builds
execute code and Docker daemon access provides powerful host control. CPU,
memory, and PID limits help availability; they do not create secure multi-tenant
isolation. Public use would require authentication, authorization, secrets,
network policy, auditability, and a stronger build sandbox.

### 23. How would you scale beyond one worker?

First remove single-JVM assumptions: use database-enforced active-deployment
admission, distributed locks or leases where truly needed, shared/object log
storage, and an event layer for SSE fan-out. Workers need host-aware scheduling,
image distribution, and reconciliation. At that point a scheduler such as
Kubernetes or Nomad may solve more than it costs.

### 24. Why not Kubernetes now?

The current constraint is one trusted host and learning the control loop.
Kubernetes would add scheduling, reconciliation, service discovery, and rolling
updates, but also a large operational surface. Deferring it makes the current
failure handling visible and gives a concrete trigger for adoption: multiple
hosts, stronger availability, or declarative workload reconciliation.

### 25. What was the hardest engineering issue?

A strong answer can use subprocess cancellation or safe replacement:

> The difficult part was treating external processes as failure-prone side
> effects. Waiting for output incorrectly could deadlock, killing only a parent
> could leave Git helpers alive, and removing the old container too early could
> cause downtime. I separated output draining from timed waiting, killed process
> descendants, and introduced candidate readiness before promotion. The focused
> tests and failure paths are the evidence.

### 26. What would you build next?

Answer with a trigger, not a technology wishlist:

- **Transactional outbox** when queued rows without messages must be recovered automatically.
- **Reverse proxy and domains** when users need stable URLs and routing switches.
- **Authentication/authorization/secrets** before more than one trusted operator uses it.
- **Metrics and reconciliation** when operating unattended becomes important.
- **Distributed scheduling** only when workloads span multiple hosts.

## Common comparisons

| Concepts | The boundary to explain |
|---|---|
| Virtual thread vs RabbitMQ | Execution context vs durable job delivery |
| H2 vs PostgreSQL | Embedded convenience vs production-like server database |
| Migration vs `ddl-auto` | Explicit versioned change vs implicit ORM schema mutation |
| Image vs container | Package/template vs running process |
| Readiness vs liveness | Can receive traffic now vs should be restarted |
| SSE vs WebSocket | One-way HTTP event stream vs full-duplex framed connection |
| Unit vs integration vs E2E | Isolated logic vs real boundary vs full user journey |
| CI vs CD | Automated integration evidence vs releasable/deployed change flow |
| Compose vs Kubernetes | Local multi-container definition vs cluster reconciliation |
| Terraform vs Kubernetes | Provision infrastructure vs operate container workloads |

## Statements to avoid

Do not say:

- “RabbitMQ guarantees exactly once.”
- “Virtual threads make the deployment durable.”
- “The database transaction rolls back Docker.”
- “Any HTTP response means healthy.”
- “This is zero downtime on a stable URL.”
- “Containers are secure virtual machines.”
- “The project is production ready for untrusted users.”
- “Kubernetes is the next step because every DevOps project needs it.”

Use precise alternatives: at-least-once plus idempotency, safe replacement,
trusted single-node scope, and evidence-backed future triggers.

## When you do not know an answer

Use this pattern:

> I have not implemented that yet. In the current design, the relevant boundary
> is ____. I would verify ____ first, then compare ____ and ____. The trade-off I
> would watch is ____.

This is stronger than guessing. Engineers are evaluated on reasoning and truth,
not pretending to know every tool.

## Self-scoring rubric

Score each practice answer from 0 to 2:

| Area | 0 | 1 | 2 |
|---|---|---|---|
| Problem | Missing | Named | Clear user and constraint |
| Mechanism | Buzzwords | Partial flow | Correct boundary and sequence |
| Failure | Ignored | Generic | Concrete detection and response |
| Trade-off | Claims perfection | Vague limitation | Honest cost and alternative |
| Evidence | None | Mentions tests | Names a relevant test/behavior |

A strong answer scores 8–10 without running longer than the interviewer needs.

## Final rehearsal checklist

- [ ] Give the 30-second explanation without notes.
- [ ] Draw the sequence and state machine.
- [ ] Explain RabbitMQ ACK plus duplicate delivery.
- [ ] Explain the dual write and outbox.
- [ ] Explain candidate promotion and its non-zero-downtime limitation.
- [ ] Show one unit test and the PostgreSQL integration test.
- [ ] State the trusted-code security boundary.
- [ ] Name one next improvement with a real trigger.
- [ ] Ask the interviewer whether they want more depth on messaging, Docker, persistence, or testing.
