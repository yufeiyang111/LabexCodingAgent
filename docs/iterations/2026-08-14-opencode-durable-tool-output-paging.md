# OpenCode durable tool-output paging alignment

## Reference snapshot

- Snapshot: `D:\opencode\opencode-dev`, `packages/opencode/package.json` version `1.17.4`.
- `packages/opencode/src/tool/truncate.ts:35-44,85-140`:
  - a fitting output is returned unchanged;
  - an oversized output retains a bounded preview, records that it was truncated, keeps a full recoverable representation, and tells the agent to use targeted inspection rather than reloading all content;
  - the truncation limits are configurable.
- `packages/opencode/src/tool/read.ts:13-18,28-36,137-180,331-375`:
  - reads are bounded;
  - `offset`/`limit` support a resumable continuation protocol;
  - the result reports the next offset and whether more content remains.

No OpenCode source was copied. This change reproduces these invariants using LabexAgent's durable multi-user transcript model rather than an OpenCode local truncation file.

## LabexAgent adaptation

- Provider `tool_call_id` remains byte-for-byte opaque after validation; blank, control-character, line-separator, and overlong IDs are rejected before they can enter durable lookup or model-visible reopen guidance.
- The authoritative full output is the existing `AgentRunPart.outputText` record. It is already written by `AgentToolCallJournalService` / `AgentRunPartService`; no additional in-memory transcript becomes a source of truth.
- `AgentContextManager` now emits a model projection plus an explicit `truncated` flag.
- `AgentLoopEngine` attaches a call-scoped hint only if the model projection actually removed source content. The hint names the original `tool_call_id` and does not expose a filesystem path.
- `read_tool_output` reads only a bounded page from a `part_type=tool` row whose `task_id`, `student_id`, and `project_id` match the active `AgentContext`.
- It uses a UTF-16 character offset to handle arbitrary non-line-oriented tool output. A continuation never splits a Unicode surrogate pair, and the response returns a machine-readable `next_offset`.
- The page maximum is centralized at `labex-agent.tool-output.read-max-chars` / `LABEX_AGENT_TOOL_OUTPUT_READ_MAX_CHARS`, default `4000`, clamped to `256..20000`.

## Deliberate boundaries

This path recovers output that was persisted in the durable Tool Part but later trimmed for a Provider request. It cannot recover data that the original tool deliberately discarded before persistence, such as a worker/preview log subject to its own hard capture limit. Those tools must retain their own artifact or pagination contract.

## Verification intent

The added regression tests cover:

1. oversized projections are marked truncated;
2. provider-loop source passes the original call id to the model projection path;
3. plan and explore modes expose `read_tool_output`;
4. task/student/project ownership rejection;
5. negative page limits;
6. an emoji surrogate-pair page boundary and exact `next_offset` formatting.

Full backend Maven verification passed on 2026-08-14: 1,623 tests, 0 failures, 0 errors, and 14 skipped tests.
