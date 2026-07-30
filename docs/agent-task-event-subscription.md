# Agent task SSE reconnection

A browser connection is an observer, not the owner of an Agent task. Refreshing or closing a tab must not cancel the task.

## Recovery flow

1. Load `GET /student/projects/{projectId}/agent/conversations/{conversationId}/active-task`.
2. Restore the returned `taskId` and original `sessionId`.
3. Connect `GET /student/projects/{projectId}/agent/tasks/{taskId}/subscribe` with `Last-Event-ID` when a durable cursor is available.
4. The server first replays persisted events after that sequence, then keeps the SSE connection open. Each connected application instance periodically catches up from `AgentRunEvent`; outbox notifications are only a low-latency hint.
5. A terminal task event closes the subscription. A browser disconnect only removes its subscription; it never invokes task cancellation.

## Event guarantees

- Every replayable event is persisted by `AgentRunLifecycleService` before delivery.
- Model `THINK_DELTA` and `FINAL_DELTA` chunks are additionally broadcast only to currently connected task subscribers. They are intentionally not replayed after a later refresh; the subsequent durable `THINK` or `FINAL` event remains the recovery source of truth.
- The task-event subscriber de-duplicates replayable events by monotonically increasing event sequence. It never advances across a sequence gap; it first reloads the missing range from `AgentRunEvent`.
- Every application instance polls durable events for its own connected subscribers every 250 ms by default (`labex.agent.task-event-poll-ms`). Therefore another JVM consuming the shared outbox row cannot make the current browser miss an approval, compaction or terminal event.
- The default in-process outbox sink remains a low-latency optimization. A shared broker is still recommended for large multi-instance deployments, but correctness no longer depends on every JVM receiving every outbox notification.
- Keepalive comments are sent every 15 seconds by default (`labex.agent.task-event-heartbeat-ms`) so intermediaries can detect a live stream.

## Explicit cancellation

Cancellation remains `POST /student/projects/{projectId}/agent/interrupt` with the restored original `sessionId`. Closing, refreshing, or navigating away from the page only closes the local subscription.

## Diagnostic log markers

The backend emits payload-free markers that can be correlated by `taskId`:

- `ACTIVE_TASK_LOOKUP_REQUEST` / `ACTIVE_TASK_LOOKUP_RESULT`
- `TASK_EVENT_SUBSCRIBE_HTTP` / `TASK_EVENT_SUBSCRIBE_REQUEST`
- `TASK_EVENT_SUBSCRIBER_REGISTERED` / `TASK_EVENT_SUBSCRIBER_REMOVED`
- `TASK_EVENT_REPLAY_COMPLETE` / `TASK_EVENT_REPLAY_FAILED`
- `TASK_EVENT_OUTBOX_BROADCAST` / `TASK_EVENT_OUTBOX_MALFORMED`
- `TASK_EVENT_DURABLE_GAP` / `TASK_EVENT_OUTBOX_DEFERRED_FOR_GAP` / `TASK_EVENT_DURABLE_POLL_FAILED`

The browser console emits `[AgentTaskRecovery]` records for recovery discovery, cursor source, subscription lifecycle, reconnects, received event ID/type, and terminal completion. Neither side logs model content, command text, authorization headers, or tokens.
