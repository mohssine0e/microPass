# Phase 2: Pipeline Coordination & Real-Time Observability

## 1. Goal of this Phase
Enhance the microPass deployment engine from a synchronous/batch processor into a production-grade asynchronous, observable pipeline:
1. **Live Real-Time Log Streaming via Server-Sent Events (SSE):** Stream every stdout/stderr line emitted by `git` and `docker` to connected clients as it happens.
2. **Container Health Checking Probing:** Automatically poll the newly spawned container endpoint over HTTP before transitioning the status from `DEPLOYING` to `HEALTHY`.
3. **Application Lifecycle Controls:** Add endpoints to stop running applications, free allocated ports, and inspect live container statuses.

---

## 2. Core Concepts & Educational Explanations

### What are Server-Sent Events (SSE)?
When observing long-running builds, a client has three communication choices:
1. **Short Polling:** The client requests `GET /deployments/1` every 1 second.
   - *Downside:* Massive HTTP overhead, wasted CPU cycles, and delayed logs.
2. **WebSockets:** A full-duplex persistent TCP connection.
   - *Downside:* Heavier protocol, requires connection upgrade handshakes, and overkill when data only flows one way (server $\to$ client).
3. **Server-Sent Events (SSE - `text/event-stream`):** Standard HTTP unidirectional streaming supported natively by all modern browsers via the `EventSource` API.
   - *Advantage:* Zero extra client dependencies, works over plain HTTP/HTTPS, auto-reconnects natively, and natively supported by Spring Web through `SseEmitter`.

### In-Memory Event Fan-Out (Log Broadcaster)
When multiple browser tabs or clients inspect the same deployment, each client needs to receive log updates simultaneously. 
We implement a thread-safe `LogStreamService` that maintains a registry of active `SseEmitter` instances keyed by `deploymentId`. When the pipeline produces a log line:
1. It is appended to the persistent build log buffer in the database.
2. It is broadcasted immediately to all currently subscribed `SseEmitter` listeners for that deployment.

### Container Health Probing
Just because `docker run` returns exit code 0 does not mean the application inside the container is ready to accept traffic.
A real PaaS introduces a **readiness health check**:
- The platform makes transient HTTP `GET` requests to `http://localhost:<port>/` with a retry backoff (e.g., up to 15 seconds).
- If the container responds with an HTTP status code, it transitions to `HEALTHY`.
- If it times out or exits prematurely, the deployment transitions to `FAILED`.

