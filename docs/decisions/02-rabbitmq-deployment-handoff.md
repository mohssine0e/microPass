# ADR 02: Use RabbitMQ for durable deployment hand-off

- Status: Accepted
- Date: 2026-11-06

## Context

A deployment can spend minutes cloning, building, starting, and probing. The
HTTP request must return quickly, but merely starting a background thread loses
the job if the JVM exits and provides no broker-controlled delivery or backpressure.

## Decision

The API stores a `QUEUED` deployment and publishes a small event to a durable
RabbitMQ direct exchange and durable queue. The consumer uses concurrency one
and prefetch one. Pipeline processing stays inside the `@RabbitListener` method,
so automatic acknowledgement occurs only after the listener returns.

Because RabbitMQ provides at-least-once delivery, the worker claims work with an
atomic conditional database update from `QUEUED` to `CLONING`. A zero-row update
means another delivery already claimed, cancelled, or recovered the job. A
dead-letter exchange and queue retain poison messages rejected by the listener.

## Consequences

- Accepted work survives the original HTTP connection and broker buffering controls pressure.
- Acknowledgement reflects completed listener handling instead of detached work.
- Duplicate delivery is expected and made harmless at the application layer.
- RabbitMQ becomes a required runtime dependency.
- Business failures are persisted as `FAILED` and normally ACKed; the DLQ is for unhandled/poison messages, not every failed build.
- The database insert and message publish remain a dual write. A transactional outbox is the next reliability step if crash-gap recovery is required.

## Alternatives considered

- A Java virtual thread: useful execution mechanism, but no durable delivery or broker backpressure.
- Database polling: fewer dependencies, but requires leasing, polling latency, and queue semantics implemented in application code.
- Kafka: powerful event log, but operationally excessive for one ordered deployment worker and simple work queues.
