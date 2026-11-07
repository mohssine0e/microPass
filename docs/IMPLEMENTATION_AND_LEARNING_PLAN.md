# microPass v0.1: Implementation and Learning Plan

> This is the living source of truth for the current work. Update the status and
> the "What happened" section whenever a phase is completed. Each phase is
> intentionally small enough to review and commit separately.

## Mission

Build a reliable, explainable single-node PaaS that is useful as a real tool and
strong enough to discuss in a software-engineering interview. The project must
teach the reasoning behind each technology, not merely include technologies as
resume keywords.

## Definition of Done for v0.1

- A user can register a Git repository and request a deployment.
- RabbitMQ durably queues the request and acknowledges it only after the worker finishes.
- Duplicate or concurrent deployment requests cannot corrupt an application workspace.
- Git and Docker processes have working cancellation and timeouts.
- A candidate container is health-checked before replacing the previous container.
- Deployment state and logs are consistent in the database and UI.
- PostgreSQL, RabbitMQ, tests, and CI have a documented setup.
- The React frontend builds successfully and uses deployable API URLs.
- The one-week learning route and interview guide teach the implemented architecture from first principles.
- Documentation describes current behavior and known limitations honestly.

## Phase 0 — Protect the Work and Establish the Map

**Status:** Complete

**Goal:** Create a stable plan, preserve existing work, and define reviewable commit boundaries.

### Tasks

- [x] Inspect the repository, documentation, source code, tests, and Git state.
- [x] Identify the product purpose and the one-week priorities.
- [x] Create this living implementation plan.
- [x] Add an accurate architecture overview and learning route.
- [x] Record all verification commands and results.

### Learning outcome

Understand the difference between a product roadmap, an implementation plan,
acceptance criteria, and a Definition of Done.

### Suggested commit

`docs: add v0.1 implementation and learning plan`

### What happened

The repository was found to be a valuable but partially consolidated prototype:
it contains two frontend approaches, a new RabbitMQ path, H2 plus early PostgreSQL
testing, and documentation that sometimes describes planned behavior as completed
behavior. The v0.1 plan therefore prioritizes correctness and explainability.
The current architecture is now documented in `docs/ARCHITECTURE.md`, and the
seven-day route turns source reading into tests, failure exercises, and spoken
explanations. No automatic commits were created because the worktree already
contained substantial user changes.

---

## Phase 1 — Reliable Commands and RabbitMQ Delivery

**Status:** Complete

**Goal:** Make asynchronous work durable, bounded, cancellable, and safe to retry.

### Tasks

- [x] Keep RabbitMQ processing inside the listener so ACK follows processing.
- [x] Limit the initial worker concurrency and prefetch to one.
- [x] Add a dead-letter exchange and queue.
- [x] Add an atomic `QUEUED -> CLONING` claim for idempotent consumption.
- [x] Reject a second active deployment for the same application.
- [x] Replace the ineffective subprocess timeout with concurrent output draining and timed waiting.
- [x] Add focused tests for timeout, cancellation, duplicate delivery, and active-deployment rejection.
- [x] Verify the backend build and tests.

### Learning outcome

Explain producer, exchange, routing key, queue, consumer, acknowledgement,
prefetch, dead-letter queue, at-least-once delivery, and idempotency. Explain why
RabbitMQ and Java virtual threads solve different problems.

### Suggested commit

`feat: make queued deployments idempotent and process execution bounded`

### What happened

The original listener started a detached virtual thread and returned immediately,
which allowed RabbitMQ to acknowledge unfinished work. The worker now owns the
pipeline call. Deployment events are atomically claimed, duplicate delivery is
safe, and command output is consumed concurrently while the caller enforces the
real timeout.
Focused tests prove process timeout, process cancellation, duplicate claim
skipping, active-deployment rejection, and queued-cancellation completion.

---

## Phase 2 — Safe Container Lifecycle and Honest State

**Status:** Complete

**Goal:** Protect the currently running application and keep database/UI state truthful.

### Tasks

- [x] Treat only HTTP 2xx/3xx responses as ready.
- [x] Avoid reserving an application's new port in the database before success.
- [x] Use a deployment-specific candidate container.
- [x] Promote the candidate only after its readiness probe succeeds.
- [x] Keep the previous container alive when candidate deployment fails.
- [x] Persist the complete success banner before completing the log stream.
- [x] Bound and release in-memory SSE replay buffers.
- [x] Make stop, delete, cancellation, and runtime-state responses consistent.
- [x] Add validation for application names, ports, and Dockerfile paths.
- [x] Add lifecycle and health-check tests.

### Learning outcome

Explain image versus container, readiness versus liveness, safe replacement,
rollback, state machines, race conditions, and why external side effects cannot
be made atomic with a database transaction by magic.

### Suggested commit

`feat: deploy health-checked candidate containers safely`

### What happened

The previous implementation removed the old container before knowing whether the
replacement was healthy. v0.1 uses a unique candidate container and only switches
the active application record after readiness succeeds. At the v0.1 milestone,
direct host-port links could still change. Optional local Caddy routing was added
later; see [ADR 06](decisions/06-stable-local-routing.md).
Queued cancellation now closes SSE immediately, stop/delete reject active work,
and stop marks the deployment that owns the actual running container even if a
newer deployment failed. API and Docker layers both enforce workspace-relative
Dockerfile paths.

---

## Phase 3 — PostgreSQL, Migrations, Testing, and CI

**Status:** Complete

**Goal:** Make persistence reproducible and prove behavior automatically.

### Tasks

- [x] Add Flyway and a versioned PostgreSQL schema.
- [x] Add development and PostgreSQL configuration profiles.
- [x] Extend Docker Compose with PostgreSQL and health checks.
- [x] Make Testcontainers use the same Flyway migrations.
- [x] Add unit tests for command, health, queue, and lifecycle behavior.
- [x] Add a GitHub Actions workflow for backend and frontend verification.
- [x] Document what requires Docker and what can run without Docker.

### Learning outcome

Explain H2 versus PostgreSQL, schema migration versus `ddl-auto=update`, unit
versus integration versus end-to-end tests, and continuous integration versus
continuous delivery.

### Suggested commit

`test: add PostgreSQL migrations integration tests and CI`

### What happened

PostgreSQL 16 now starts from Compose, Flyway owns the versioned schema, and the
`postgres` profile uses Hibernate validation rather than schema mutation. A real
PostgreSQL Testcontainers test applies the same migration. Testcontainers was
aligned to 1.21.4 with docker-java 3.7.1 so it negotiates correctly with Docker
Engine 29. CI builds React, runs backend verification, and proves the React entry
point is present inside the executable JAR.

---

## Phase 4 — One Coherent React Product

**Status:** Complete

**Goal:** Make the React dashboard the clear product UI and correct its lifecycle behavior.

### Tasks

- [x] Use a relative/configurable API base URL and a Vite development proxy.
- [x] Close SSE connections on the explicit terminal event.
- [x] Correct stopped, failed-last-deploy, and running status presentation.
- [x] Surface API errors to the user instead of only logging them to the console.
- [x] Document the production frontend build and Spring Boot integration path.
- [x] Mark the older Thymeleaf UI as legacy until it is removed safely.

### Learning outcome

Explain browser same-origin rules, CORS, development proxying, SPA build versus
runtime, API error handling, and client/server state synchronization.

### Suggested commit

`feat: consolidate the dashboard around the React client`

### What happened

React is the primary UI at `/`; `/ui` is documented as the legacy Thymeleaf
prototype. The same relative API calls work in Vite development and the packaged
JAR. Visible banners report API/action failures, status combines deployment
history with actual Docker runtime state, and active streams close on the named
terminal event. `scripts/build-all.sh` performs a reproducible combined build.

---

## Phase 5 — Interactive microPass Engineering Academy

**Status:** Complete; maintained in a separate local repository since 2026-11-06

**Goal:** Create an offline, attractive learning system tied directly to this repository.

### Tasks

- [x] Create an animated architecture journey from HTTP request to healthy container.
- [x] Add beginner-first modules for Java/Spring, RabbitMQ, PostgreSQL, Docker, SSE/React, testing/CI, and cloud concepts.
- [x] Add comparison cards for commonly confused concepts.
- [x] Add flashcards, quizzes with explanations, and interview scenarios.
- [x] Add a structured interview-answer builder.
- [x] Add editable notes, confidence tracking, and progress saved in `localStorage`.
- [x] Respect keyboard navigation and reduced-motion preferences.
- [x] Document how to open and use the academy offline.

### Learning outcome

Build a mental model, practice recall, explain trade-offs, and turn implementation
experience into clear interview answers.

### Suggested commit

`docs: add interactive microPass engineering academy`

### What happened

The offline academy contains eight guided lessons, an interactive deployment
journey, comparison lab, 16 flashcards, 12 scenario questions, model interview
answers, a searchable glossary, and a persistent notebook. Progress, confidence,
theme, quiz score, and notes are local to the browser. JavaScript syntax and real
desktop/mobile browser rendering were verified; the hero was adjusted so useful
content is visible immediately while later sections retain reveal animations.
The Academy was moved out of the product repository so future product commits
do not publish personal learning progress or curriculum changes.

---

## Phase 6 — Documentation, Demo, and Interview Readiness

**Status:** Complete

**Goal:** Make the project reproducible, honest, and easy to present.

### Tasks

- [x] Update README architecture, prerequisites, commands, endpoints, and limitations.
- [x] Add a five-minute demo script and failure demonstrations.
- [x] Add architecture decision records for RabbitMQ, PostgreSQL, SSE, and safe replacement.
- [x] Add an interview question bank with beginner and deep follow-ups.
- [x] Record final build/test results and remaining Docker-environment limitations.
- [x] Review the final Git diff for accidental or unrelated changes.

### Learning outcome

Explain the system using: problem, constraints, design, flow, failure modes,
trade-offs, evidence, and next improvements.

### Suggested commit

`docs: prepare microPass v0.1 demo and interview guide`

### What happened

The README now describes the current React/RabbitMQ/PostgreSQL architecture and
states its security and operational limits directly. Architecture, four new
ADRs, a five-minute demo, 26 interview questions with model answers, a scoring
rubric, and a one-week learning route were added. The obsolete deep-dive was
replaced with a compatibility index so it no longer teaches the prototype as the
current design. A full isolated smoke deployment verified the packaged UI,
RabbitMQ hand-off, Git clone, Docker build/run, readiness, SSE history/completion,
cancellation while preserving the previous healthy container, and delete cleanup
without changing existing Docker services.

---

## Deferred Deliberately

These are valuable, but adding them before v0.1 is stable would weaken the project:

- Kubernetes execution backend and Helm
- Terraform/OpenTofu cloud provisioning
- Multi-user authentication and authorization
- Distributed workers across multiple hosts
- Automatic TLS and domain routing
- Prometheus/Grafana dashboards beyond a minimal metrics foundation
- Secrets encryption and external secret stores

They remain future learning tracks, each requiring a real use case and acceptance criteria.

## Suggested Staging and Commit Boundaries

The worktree contained user changes before this plan, so review and stage each
boundary deliberately. Some implementation files span two phases; use
`git add -p` when you want perfectly separated commits.

| Boundary | Main paths | Suggested commit |
|---|---|---|
| Plan and architecture map | `docs/IMPLEMENTATION_AND_LEARNING_PLAN.md`, `docs/ARCHITECTURE.md` | `docs: add v0.1 implementation and learning plan` |
| Queue and command reliability | `CommandExecutor`, `RabbitConfig`, `DeploymentWorker`, atomic repository transition, focused tests | `feat: make queued deployments idempotent and process execution bounded` |
| Safe lifecycle and API rules | pipeline, Docker/health/port/log services, controllers, lifecycle tests | `feat: deploy health-checked candidate containers safely` |
| PostgreSQL and CI | backend POM, profile, migration, Compose, Testcontainers, workflow | `test: add PostgreSQL migrations integration tests and CI` |
| React product packaging | `frontend/`, Maven static resource copy, `scripts/build-all.sh` | `feat: consolidate the dashboard around the React client` |
| Engineering Academy | Separate local repository | Local Academy commits only |
| Demo and interview readiness | README, ADRs, one-week route, demo, interview guide | `docs: prepare microPass v0.1 demo and interview guide` |

## Verification Log

| Date | Command | Result | Notes |
|---|---|---|---|
| 2026-11-06 | `npm run build` | Passed | React production bundle compiled. |
| 2026-11-06 | `npm audit` | Passed | Vite 8.3.6 toolchain reports zero known advisories. |
| 2026-11-06 | `mvn -DskipTests package` | Passed | Backend executable JAR built. |
| 2026-11-06 | Focused backend suite | Passed | 13 tests passed: command, health, queue/idempotency, atomic cancellation, lifecycle, path validation. |
| 2026-11-06 | PostgreSQL Testcontainers test | Passed | PostgreSQL 16 started, Flyway V1 applied, repository relations persisted. |
| 2026-11-06 | Academy JavaScript syntax check | Passed | Verified before moving Academy to its local repository. |
| 2026-11-06 | Headless Firefox desktop/mobile renders | Passed | Academy hero and responsive layout inspected visually. |
| 2026-11-06 | Isolated full-stack smoke deployment | Passed | Packaged React, RabbitMQ, Git, Docker, readiness, SSE, cancellation with previous-container survival, status, and delete cleanup verified. |
| 2026-11-06 | `./scripts/build-all.sh` | Passed | Vite 8 build, 20 Maven tests, PostgreSQL migration, executable JAR, and embedded React check passed. |
