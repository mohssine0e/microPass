# microPass Five-Minute Demo Script

The goal is to show a clear engineering story, not click through every screen.
Practice until you can speak while the deployment runs.

## Before the interview

```bash
docker compose up -d postgres rabbitmq
./scripts/build-all.sh -DskipTests
SPRING_PROFILES_ACTIVE=postgres java -jar backend/target/micropass-0.0.1-SNAPSHOT.jar
```

Verify:

- `http://localhost:9090/` opens the React dashboard.
- `http://localhost:15672/` opens RabbitMQ management.
- Docker is running.
- The absolute path to `fixtures/sample-app` is ready.
- Remove or rename an earlier `sample-app` record so the demo starts cleanly.

Keep these tabs open: dashboard, RabbitMQ queue view, and optionally a terminal
with `docker ps`.

## 0:00–0:40 — State the problem

Say:

> microPass is a trusted single-node deployment platform. Instead of manually
> cloning a repository, building an image, finding a port, starting a container,
> checking readiness, and collecting logs, the user registers a Git repository
> and requests one observable deployment.

Briefly point out the honest boundary: one machine, trusted code, no domains or
authentication yet.

## 0:40–1:20 — Register the sample

Register:

- Name: `sample-app`
- Repository: absolute path to `fixtures/sample-app`
- Branch: `main`
- Dockerfile: `Dockerfile`
- Internal port: `80`

Say:

> The API validates the name, port, and repository-relative Dockerfile path.
> PostgreSQL stores the application; the UI is not the authority for validation.

## 1:20–2:40 — Deploy and narrate the flow

Click **Deploy** and immediately open logs.

Say as states appear:

> The API creates a `QUEUED` deployment and publishes a small event to RabbitMQ,
> then returns `202 Accepted`. The worker claims the row atomically, synchronizes
> Git, builds an image, allocates a host port, and starts a resource-limited
> candidate container. The broker ACK happens after listener handling returns.

Point out one or two log lines instead of reading all output.

## 2:40–3:30 — Show readiness and runtime truth

When the status becomes `HEALTHY`, open the sample app from its host-port link.
Optionally show:

```bash
docker ps --filter name=micropass-sample-app
```

Say:

> `docker run` was not considered success. microPass waited for HTTP 2xx or 3xx,
> stored the candidate as active, then retired the previous container. The status
> endpoint also inspects Docker, because database intent and runtime reality can differ.

## 3:30–4:20 — Show one reliability behavior

Choose one repeatable option.

### Option A: active-deployment conflict

Start a deployment and click deploy again before it completes. Show `409` in the
UI and explain that one active deployment per app protects the shared Git cache
and lifecycle state.

### Option B: cancellation

Start a deployment and click **Cancel Build**. Show `CANCELLED` and explain that
the service sets a cancellation flag, kills the active process tree, persists
the terminal state, and closes the SSE stream.

### Option C: queue unavailable

```bash
docker compose stop rabbitmq
```

Trigger a deployment. Show the visible `503` error and failed deployment, then:

```bash
docker compose start rabbitmq
```

Explain that microPass does not silently bypass its required durable queue.

## 4:20–5:00 — Evidence, trade-off, and next step

Say:

> I test command timeout/cancellation, duplicate delivery, active-deployment
> rejection, Dockerfile boundaries, health status rules, lifecycle stop behavior,
> and the real PostgreSQL migration with Testcontainers. CI builds React and
> verifies it is packaged in the Spring Boot JAR.

> The main limitation is that database insertion and RabbitMQ publication are a
> dual write. My next reliability step would be a transactional outbox. For user-
> facing routing, the next step would be a reverse proxy with stable domains.

Stop speaking there and invite questions.

## Failure demonstrations to prepare, not improvise

| Demonstration | What it proves | Restore action |
|---|---|---|
| Stop RabbitMQ before deploy | Required dependency and visible failure | `docker compose start rabbitmq` |
| Cancel during Git/build | Process cancellation and terminal state | Trigger a new deployment |
| Submit `../Dockerfile` through API | Workspace boundary validation | Use `Dockerfile` |
| Run PostgreSQL integration test | Real schema/migration evidence | Testcontainers cleans itself |

Do not intentionally delete Docker data, reset the repository, or modify the
main fixture during a live interview.
