# microPass: How It Works and How to Explain It

This path is kept for compatibility with older project links. The original
document described an earlier prototype that started deployments in detached
virtual threads and replaced containers before the final v0.1 reliability work.
That description is no longer accurate.

Use these current sources instead:

1. [Architecture](../ARCHITECTURE.md) — exact request flow, components, state
   machine, concurrency model, failure behavior, and code trace
2. [One-week engineering route](ONE_WEEK_ENGINEERING_ROUTE.md) — daily reading,
   proof exercises, explanations, and answers
3. [Interview rehearsal guide](../INTERVIEW_REHEARSAL.md) — 30/90-second
   explanations, 26 questions with answers, cautions, and a scoring rubric
4. [Five-minute demo](../DEMO_SCRIPT.md) — repeatable live presentation and
   failure demonstrations
5. [Architecture Decision Records](../decisions/) — why RabbitMQ, PostgreSQL,
   Flyway, SSE, and candidate promotion were selected

## Current flow in one paragraph

The Spring API validates a deployment request, saves a `QUEUED` record, publishes
an event to RabbitMQ, and returns HTTP `202`. The listener performs the pipeline
before ACK: an atomic database transition claims the event, Git synchronizes the
selected branch, Docker builds an image and starts a resource-limited candidate,
and an HTTP 2xx/3xx readiness response is required before promotion. A failed
candidate is removed while the previous active container remains. PostgreSQL and
Flyway persist reproducible state; SSE streams bounded live logs and sends an
explicit final event to React.

## The four statements to remember

- RabbitMQ provides at-least-once delivery; the atomic state transition provides application idempotency.
- Virtual threads make blocking work cheaper; they do not provide durable job delivery.
- A database transaction cannot roll back Git, RabbitMQ, or Docker side effects.
- v0.1 provides safe replacement, not stable-domain zero-downtime routing.
