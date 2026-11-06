# ADR 04: Use Server-Sent Events for live deployment logs

- Status: Accepted
- Date: 2026-11-06

## Context

Deployment output is an ordered, mostly text-based stream from server to
browser. Polling adds latency and repeated requests. A bidirectional WebSocket
protocol would add machinery that the current log use case does not require.

## Decision

Expose deployment logs as `text/event-stream` through Spring `SseEmitter` and
consume them with browser `EventSource`. Keep a bounded 10,000-line replay buffer
for active deployments, persist the complete log in PostgreSQL, and send a named
`complete` event with the final status. The React client closes the connection
on that event.

## Consequences

- The browser receives low-latency logs over normal HTTP with a native API.
- Late subscribers receive active buffered output; completed subscribers receive database history.
- The explicit terminal event prevents automatic EventSource reconnect loops.
- Replay memory is bounded and released on completion.
- In-memory emitters and buffers belong to one JVM and do not support horizontal scaling by themselves.
- Very large logs still make one database text field; an external log store is deferred.

## Alternatives considered

- Polling: simple but inefficient and less responsive.
- WebSockets: appropriate for richer bidirectional protocols, unnecessary for one-way logs today.
- External log platform: operationally stronger at scale, but too large for the current single-node scope.
