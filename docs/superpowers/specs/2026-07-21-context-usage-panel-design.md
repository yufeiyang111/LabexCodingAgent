# Context Usage Panel Design

## Goal

Add a Claude-inspired, real-time context-status experience to the Agent chat workspace. The experience must show how much of the **next model request's context** is occupied, distinguish its contributors, and never present cumulative billing usage as current context usage.

## Scope

- Show a compact context ring next to the chat composer actions.
- Show an expanded hover card with the current context amount and percentage.
- Show a detailed modal opened from the chat panel's upper-right context entry point or by clicking the compact ring.
- Measure the full assembled model-request prompt before each provider call and after any trim/compaction operation.
- Hydrate the latest snapshot through an authenticated API and update it via the existing Agent SSE stream.
- Explicitly label first-release token values as character-derived estimates.

Out of scope:

- Changing historical token/billing reports.
- Inferring unconfigured context-window capacities from model names.
- Adding a charting dependency or using ECharts for this panel.
- Retrofitting exact tokenizer support for every OpenAI-compatible provider.

## User experience

### Compact composer indicator

The chat composer displays an unobtrusive 22px circular progress ring near its lower action row.

- It has no embedded text or number.
- It is keyboard-accessible with an aria label describing the current state.
- The ring color represents the current percentage when the window is known:
  - under 70%: the existing blue accent;
  - 70% through 89%: amber;
  - 90% or above: coral/red;
  - unknown window: neutral gray.
- Hovering or keyboard focusing it opens a small dark, elevated card. The card shows `current / window tokens`, the percentage, a thin segmented contribution strip, and the `字符估算` measurement label.
- Clicking the ring opens the detailed modal.
- If no snapshot exists yet, the ring is neutral and the hover card explains that context data will appear before the first model call.

### Detailed context modal

A context button in the chat panel's upper-right action area opens the same modal. The modal follows the approved Claude-style dark visual language:

1. Header: `上下文使用情况`, close control, and currently selected provider/model.
2. Summary: `current / context window tokens (percentage)` and a rounded segmented horizontal progress bar.
3. Category table: color marker, category, estimated tokens, and fraction of the configured window.
4. Remaining-space row in a subdued color.
5. Footer: states that the snapshot updates before every model call and after context cleanup, and that values are character-derived estimates.

The first release renders five categories:

- System prompt;
- Tool definitions;
- Workspace memory;
- Skills and instructions;
- Conversation and tool results.

Empty categories render as `0` and remain visible so the data contract and color meanings stay stable. Categories are measured against the context-window capacity, not against only the currently used total, matching the supplied reference design.

The visual implementation uses local CSS progress elements and Vue transitions only. It does not introduce ECharts or a new frontend dependency.

## Backend design

### Runtime context snapshot

Create a focused runtime model such as `ContextUsageSnapshot`, plus a `ContextUsageEstimator` service responsible only for calculating categorized prompt estimates. It must not be placed in `TokenTracker`, whose responsibility is historical provider-usage persistence.

Immediately before each provider request, the estimator counts the complete prompt shape sent to the provider:

- final system prompt;
- retained conversation messages, classifying tool-result messages separately from ordinary conversation content;
- serialized tool schemas;
- workspace-memory and skills/instructions portions provided during prompt assembly.

The first release retains the existing project convention of estimated tokens based on character length, but applies it consistently with a single estimator and sets `measurementSource` to `ESTIMATED_CHARS`.

The tracker stores the most recent snapshot by conversation/session in a safe concurrent runtime registry. It publishes a fresh snapshot:

- after initial request preparation;
- immediately before every model call;
- after tool-result pruning;
- after deterministic checkpoint compaction; and
- after aggressive trimming.

A snapshot includes:

```text
conversationId
sessionId
provider
model
contextWindowTokens (nullable)
usedTokens
usagePercent (nullable when window is unknown)
measurementSource = ESTIMATED_CHARS
categories: systemPrompt, toolDefinitions, workspaceMemory, skillsAndInstructions,
            conversationMessages, toolResults
trimState: NONE | TOOL_RESULT_PRUNE | CHECKPOINT_COMPACTION | AGGRESSIVE
updatedAt
```

`AgentModelConfig.contextWindowTokens` is the only source for the displayed context capacity. `maxTokens` remains output-token capacity and is never labelled or treated as a context window.

### API and SSE

Add an ownership-scoped endpoint:

```text
GET /student/projects/{projectId}/agent/conversations/{conversationId}/context-status
```

The controller first resolves the conversation for the authenticated project owner using existing conversation-service authorization patterns. If there is no in-memory snapshot, it returns a safe empty status rather than data from another conversation or a fabricated estimate.

Extend the existing stream event pipeline with a `CONTEXT_STATUS` event that contains the same response shape. The UI loads the endpoint when a conversation is selected, then replaces its local status whenever the stream emits this event. Reconnect/reload therefore retains the last current-run status while the service process remains available.

No current database migration is required for this display-only, per-run snapshot. Historical token records stay separate from context status and retain their existing semantics.

## Frontend architecture

Keep `CloudWorkspace.vue` as orchestration only. Add two focused components under `frontend/src/components/cloud/`:

- `ContextUsageIndicator.vue`: compact circular ring, accessible hover/focus card, formatting and threshold color logic, and an `open` event.
- `ContextUsageDialog.vue`: dialog layout, category table, progress strip, empty/unknown-window states, and close handling.

Add a small, domain-named frontend helper/composable if necessary for status normalization and token formatting; do not add this logic to a general-purpose utilities dumping ground.

`CloudWorkspace.vue` owns:

- the reactive `contextStatus` state;
- loading the status after conversation selection;
- routing `CONTEXT_STATUS` stream events into that state; and
- opening/closing the dialog.

`frontend/src/api/index.js` gains only the centralized `agentContextStatus(projectId, conversationId)` request method.

## Error handling and edge cases

- Missing active conversation: show neutral compact state; suppress API request.
- Context window not configured: show used estimated tokens but no percentage, use neutral ring/progress styling, and state `未配置上下文窗口` in the hover card/modal.
- No request snapshot yet: show an explicit awaiting-first-request state.
- API failure: retain a previously received live snapshot; otherwise show the neutral unavailable state without a toast loop.
- SSE reconnect: refresh endpoint status after stream reconnection using the active conversation ID.
- Very high usage: color changes are informative only; no automatic blocking or altered pruning policy is introduced by this UI.

## Verification

### Backend

- Unit-test the estimator with representative system prompt, ordinary messages, tool-result messages, tool schema payloads, workspace memory, and skill content.
- Test percentage behavior for configured, zero, and missing context windows.
- Test trim-state snapshots are emitted after each supported cleanup path.
- Test the endpoint rejects/does not reveal another user's conversation status.
- Run the focused Maven tests, then `mvn test` if the suite is practical in the local environment.

### Frontend

- Add focused component tests for ring state/color thresholds, unknown-window display, hover content, and dialog categories.
- Verify stream-event normalization updates the displayed status.
- Run `npm run build` from `frontend/`.

## Trade-offs

The first release provides transparent, consistent **estimates**, not provider-tokenizer-exact accounting. This is intentional: OpenAI-compatible providers do not expose a universal tokenizer or context-capacity metadata contract. The UI must communicate this limitation rather than imply false precision. A future provider-specific tokenizer/capability layer can replace `ESTIMATED_CHARS` with exact request-token counts without changing the API or component contracts.
