# One-Week microPass Engineering Route

This route assumes you are learning several topics for the first time. The goal
is not to memorize every class. By the end of the week, you should be able to
trace a deployment, predict important failures, run the evidence, and explain
why each technology exists.

## How to study each day

Use three blocks:

1. **Learn — 45 minutes:** read the academy lesson and the linked source.
2. **Prove — 45 minutes:** run, test, or deliberately break one behavior.
3. **Explain — 20 minutes:** close the notes and speak for 60–90 seconds.

Use [LEARNING_JOURNAL.md](academy/LEARNING_JOURNAL.md) after each session. If you
cannot explain a concept without reading, mark it as “recognize” rather than
“understand” and revisit it.

## Day 1 — Product, HTTP, Spring, and the state machine

### Mental model

- A **product requirement** says what outcome the user needs.
- An **API** is a contract for asking the backend to perform or report work.
- A **controller** translates HTTP into application calls.
- A **service** coordinates business rules and side effects.
- A **repository** reads and writes domain data.
- A **state machine** gives long-running work named stages and valid outcomes.

### Read

- [Architecture](../ARCHITECTURE.md): purpose, component map, sequence, states
- `AppController.java`
- `DeploymentPipelineService.triggerDeployment`
- `DeploymentStatus.java`
- Academy modules: **System mental model** and **Java 21 and Spring Boot**

### Prove

- [ ] Start dependencies and the backend.
- [ ] Call `GET /api/apps` and identify request, controller, repository, and JSON response.
- [ ] Register one app and inspect its database-facing fields in the response.
- [ ] Trigger a deployment and explain why the response is `202`, not `200 success` for the final deployment.
- [ ] Draw `QUEUED → CLONING → BUILDING → DEPLOYING → HEALTHY` from memory.

### Explain aloud

> microPass is a control plane that turns deployment intent into durable state
> and coordinated Git/Docker actions. The HTTP request starts the workflow but
> does not perform all the work before responding.

### Check yourself

**Why should the React UI not be the authority for validation?**

Because any caller can bypass the UI and call the API. The backend protects the
system boundary and must enforce names, ports, paths, and lifecycle rules.

**What is the difference between application state and deployment state?**

The application records the currently active runtime container and port. Each
deployment records one attempt. A failed latest attempt can coexist with an
older active container.

## Day 2 — Operating-system processes, Git, timeout, and cancellation

### Mental model

Java starts `git` and `docker` as operating-system child processes. Those tools
can create their own descendants and can block while producing output. The JVM
must drain output, enforce time limits, and terminate the whole process tree.

### Read

- `CommandExecutor.java`
- `GitService.java`
- `WorkspaceService.java`
- `CommandExecutorTest.java`
- `docs/troubleshooting/git-curl-http2-stream-cancel.md`

### Prove

- [ ] Run `CommandExecutorTest`.
- [ ] Explain why reading all output only after `waitFor()` can deadlock when a pipe fills.
- [ ] Find where stdout and stderr are merged.
- [ ] Find where the active `Process` reference is stored for immediate cancellation.
- [ ] Inspect the persistent app Git workspace and explain incremental fetch versus fresh clone.
- [ ] Cancel a build and verify the deployment becomes `CANCELLED`.

### Explain aloud

> A timeout is a policy for how long work may run. Cancellation is an operator
> decision that should stop promptly. Both must terminate descendant processes,
> because killing only the parent can leave Git workers alive.

### Check yourself

**Why use a separate output-reader thread?**

The child process can block when its output pipe is full. Concurrent draining
lets the process continue while the caller independently checks completion,
timeout, and cancellation.

**Does a virtual thread make the Git operation durable?**

No. It makes blocking concurrency cheaper inside one JVM. Durability across JVM
failure comes from persisted state and RabbitMQ delivery.

## Day 3 — RabbitMQ, acknowledgements, and idempotency

### Mental model

```text
producer → exchange → routing key → queue → consumer → ACK
```

- The producer publishes an event.
- The exchange routes it through bindings.
- The queue stores it until delivery.
- The consumer handles it.
- ACK tells RabbitMQ that the delivery can be removed.
- At-least-once delivery means duplicates are possible.
- Idempotency makes a repeated delivery harmless.

### Read

- [ADR 02](../decisions/02-rabbitmq-deployment-handoff.md)
- `RabbitConfig.java`
- `DeploymentWorker.java`
- `DeploymentRepository.transitionStatus`
- `DeploymentPipelineService.runPipeline`
- Academy module: **RabbitMQ and job delivery**

### Prove

- [ ] Open RabbitMQ management and locate the exchange, deployment queue, and DLQ.
- [ ] Explain `concurrency=1`, `max-concurrency=1`, and `prefetch=1`.
- [ ] Find the listener and prove no detached thread is started from it.
- [ ] Run the duplicate-delivery test.
- [ ] Trigger a second deployment while one is active and observe `409 Conflict`.
- [ ] Stop RabbitMQ, trigger a deployment, observe `503`, then start RabbitMQ again.

### Explain aloud

> RabbitMQ decouples accepting work from executing it. The listener does not ACK
> until handling returns. Because a crash can still cause redelivery, the worker
> atomically claims only a row still in QUEUED.

### Check yourself

**Why is ACK-after-processing still not exactly once?**

The worker can finish an external effect and crash before the broker receives
the ACK. RabbitMQ then redelivers. Exactly-once business behavior requires
idempotent state transitions and reconciliation, not a promise from ACK alone.

**When does the DLQ receive a message?**

When the queue rejects or dead-letters an unhandled/poison delivery. A normal
application build failure is caught, saved as `FAILED`, and ACKed; it is not sent
to the DLQ merely because the deployed code failed.

## Day 4 — PostgreSQL, Flyway, transactions, and dual writes

### Mental model

- A relational database stores structured durable state with constraints.
- A transaction makes database operations commit or roll back together.
- A migration is a versioned schema change.
- Hibernate maps Java entities to tables.
- A database transaction cannot roll back Docker or RabbitMQ by itself.

### Read

- [ADR 03](../decisions/03-postgresql-and-flyway.md)
- `App.java` and `Deployment.java`
- `V1__create_micropass_schema.sql`
- `application-postgres.properties`
- `AppRepositoryPostgresIntegrationTest.java`
- Academy module: **PostgreSQL and transactions**

### Prove

- [ ] Start with the `postgres` profile and watch Flyway validate/apply migrations.
- [ ] Inspect the `flyway_schema_history` table.
- [ ] Run the PostgreSQL Testcontainers integration test.
- [ ] Match every entity field to its SQL column.
- [ ] Find the foreign key and indexes and explain their purpose.
- [ ] Describe the crash window between inserting `QUEUED` and publishing to RabbitMQ.

### Explain aloud

> Flyway owns schema evolution and Hibernate validates the mapping. PostgreSQL
> makes deployment state durable, but Git, Docker, and RabbitMQ are independent
> systems, so their side effects require explicit failure handling.

### Check yourself

**H2 versus PostgreSQL?**

H2 is convenient and embedded; PostgreSQL is the production-like server
database. Passing H2 tests does not prove PostgreSQL SQL, types, or migrations.

**What would a transactional outbox change?**

The API would commit the deployment and an outbox row in one database
transaction. A relay would publish outbox rows to RabbitMQ and mark them sent,
closing the sudden-crash gap between database insert and broker publish.

## Day 5 — Docker, networking, health, and safe replacement

### Mental model

- A **Dockerfile** is a recipe.
- An **image** is the built immutable package.
- A **container** is a running process created from an image.
- A **host port** is reachable on the machine; a **container port** is inside its network namespace.
- **Readiness** asks whether traffic can be served now.
- **Liveness** asks whether an instance should be restarted.

### Read

- [ADR 05](../decisions/05-health-checked-candidate-replacement.md)
- `DockerService.java`
- `PortAllocationService.java`
- `HealthCheckService.java`
- The promotion section of `DeploymentPipelineService.runPipeline`
- Academy module: **Docker and deployment safety**

### Prove

- [ ] Deploy `fixtures/sample-app`.
- [ ] Inspect the image and container with `docker image ls` and `docker ps`.
- [ ] Explain `hostPort:containerPort` using the actual values.
- [ ] Find memory, CPU, and PID limits in `docker inspect`.
- [ ] Run the health-check and Dockerfile-boundary tests.
- [ ] Explain why the previous container id is read before promotion.

### Explain aloud

> microPass does not trust `docker run` as proof of readiness. It starts a unique
> candidate, probes for HTTP 2xx/3xx, updates the active record only on success,
> and removes the previous container afterward.

### Check yourself

**Is this zero downtime?**

It is safe replacement because the known-good container survives candidate
failure. It is not stable-URL zero downtime because there is no reverse proxy
atomically switching a fixed domain or port.

**Why is port probing not a perfect reservation?**

The probe socket closes before Docker binds the port. Another process could bind
in between. Serial deployment greatly reduces the window but does not make the
two side effects atomic.

## Day 6 — SSE, React, testing, and CI

### Mental model

- Normal HTTP produces one response; SSE keeps a response open for many text events.
- `EventSource` reconnects automatically unless the client deliberately closes it.
- Unit tests isolate logic; integration tests verify real boundaries; end-to-end tests verify a user journey.
- CI repeats verification in a clean environment on each change.

### Read

- [ADR 04](../decisions/04-sse-for-deployment-logs.md)
- `LogStreamService.java`
- `frontend/src/api.ts`
- `LogConsoleModal.tsx`
- `.github/workflows/ci.yml`
- Academy modules: **SSE, HTTP, and React** and **Testing and CI**

### Prove

- [ ] Open a deployment console after several lines already exist and observe replay.
- [ ] Watch the browser close the stream after the named `complete` event.
- [ ] Explain why the active replay buffer is bounded.
- [ ] Run the 12 focused backend tests and the React production build.
- [ ] Identify what the PostgreSQL integration test proves that mocks cannot.
- [ ] Open the packaged JAR and locate `BOOT-INF/classes/static/index.html`.

### Explain aloud

> SSE fits because logs flow one way. The server replays bounded active history,
> persists complete history, and sends a terminal event so EventSource does not
> reconnect forever. CI proves both the backend and packaged React build.

### Check yourself

**When would WebSockets be a better choice?**

When the same long-lived channel needs frequent bidirectional messages, custom
commands, binary frames, or multiplexing that simple HTTP endpoints plus SSE do
not handle well.

**Why not make every test end-to-end?**

End-to-end tests are slower, more fragile, and less precise about the cause of a
failure. Keep many fast unit tests, real integration tests for critical
boundaries, and a small number of full journeys.

## Day 7 — Cloud/DevOps reasoning, demo, and interview rehearsal

### Mental model

- Docker packages/runs containers on a host.
- Compose describes a local multi-container application.
- Terraform/OpenTofu provisions infrastructure through provider APIs.
- Kubernetes reconciles workloads across a cluster.
- CI verifies integrated changes; delivery makes artifacts releasable; deployment runs them in an environment.
- Metrics show numeric trends, logs explain events, and traces follow requests across boundaries.

### Read

- Academy module: **Cloud and DevOps foundations**
- [Demo script](../DEMO_SCRIPT.md)
- [Interview rehearsal](../INTERVIEW_REHEARSAL.md)
- “Deferred deliberately” in the implementation plan

### Prove

- [ ] Perform the five-minute demo without reading every line.
- [ ] Explain one successful and one failed/cancelled deployment.
- [ ] Give the 30-second and 90-second project explanations.
- [ ] Answer ten interview questions aloud and record weak answers.
- [ ] Name three things Kubernetes would solve and three costs it would add.
- [ ] State the security boundary without exaggerating production readiness.

### Final explanation target

Use this order:

1. Problem and user
2. Constraints and scope
3. End-to-end flow
4. Two important design decisions
5. Failure behavior
6. Test evidence
7. Honest limitations
8. Next improvement and its trigger

## End-of-week competency checklist

- [ ] I can draw the system without looking.
- [ ] I can explain RabbitMQ versus virtual threads.
- [ ] I can explain at-least-once delivery and the atomic claim.
- [ ] I can explain PostgreSQL transactions and the dual-write limitation.
- [ ] I can explain image, container, ports, readiness, and safe replacement.
- [ ] I can explain how cancellation kills a process tree.
- [ ] I can compare SSE, polling, and WebSockets.
- [ ] I can choose unit, integration, or end-to-end testing for a risk.
- [ ] I can explain why Kubernetes and Terraform are deferred.
- [ ] I can demonstrate the product and discuss one failure without hiding it.
