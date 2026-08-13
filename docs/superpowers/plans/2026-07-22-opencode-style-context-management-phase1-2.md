# OpenCode-Style Context Management Phase 1-2 Implementation Plan

> **For agentic workers:** Implement task-by-task with focused tests before production code. This plan intentionally excludes the later dedicated LLM compaction agent and full persisted compaction-event chain.

**Goal:** Replace LabexAgent's fixed-message hard truncation with model-configured context budgeting, pre-overflow warnings, turn-aware recent-context retention, and audit-safe pruning of older tool results.

**Architecture:** A new `ContextWindowPolicy` derives an input budget, reserve buffer, tail budget, and pruning settings from `AgentModelConfig`. `ContextWindowSupervisor` evaluates the full prompt footprint, including tool schemas, and produces a decision of `NONE`, `PRUNE`, or `CHECKPOINT`. A turn-aware pruner keeps recent user turns within a token budget, safely replaces only eligible historical tool-result content in the runtime prompt, and leaves persisted `AgentMessage` audit data untouched. The existing deterministic `ConversationCheckpointCompactor` remains the overflow and high-pressure fallback.

**Tech Stack:** Java 17, Spring Boot 3, MyBatis-Plus, MySQL schema initialization/additive migration, Vue 3, JUnit 5.

## Global Constraints

- No provider/LLM call is added in Phase 1-2.
- Existing `t_agent_message` content remains the immutable audit source; pruning applies only to runtime prompt messages.
- Existing overflow checkpoint compaction remains the final deterministic fallback.
- All new model settings default safely and preserve current behavior when absent.
- Do not commit, push, reset, or clean the already-dirty workspace.

---

### Task 1: Add model-level compaction policy and validation

**Files:**
- Modify: `backend/src/main/java/com/labex/entity/AgentModelConfig.java`
- Modify: `backend/src/main/java/com/labex/service/AgentModelConfigService.java`
- Modify: `backend/src/main/java/com/labex/config/AdditiveSchemaMigrator.java`
- Modify: `backend/src/main/resources/sql/schema.sql`
- Modify: `frontend/src/components/cloud/ModelConfigDialog.vue`
- Modify: `frontend/src/views/CloudWorkspace.vue`
- Test: `backend/src/test/java/com/labex/service/AgentModelConfigServiceTest.java` or an existing model-config regression test.

**Data contract:**
- `compaction_auto`: boolean-like integer, default enabled.
- `compaction_prune`: boolean-like integer, default disabled.
- `compaction_tail_turns`: nullable positive integer, default 2.
- `compaction_preserve_recent_tokens`: nullable positive integer.
- `compaction_reserved_tokens`: nullable non-negative integer.

- [ ] Write failing validation/default tests.
- [ ] Add fields, schema, additive migration, effective-value validation, and UI advanced settings.
- [ ] Run focused tests and frontend build.

### Task 2: Create an explicit context-window policy and footprint evaluator

**Files:**
- Create: `backend/src/main/java/com/labex/labexagent/runtime/ContextWindowPolicy.java`
- Create: `backend/src/main/java/com/labex/labexagent/runtime/ContextWindowSupervisor.java`
- Modify: `backend/src/main/java/com/labex/labexagent/runtime/ContextBudgetResolver.java`
- Modify: `backend/src/main/java/com/labex/labexagent/runtime/ContextUsageEstimator.java`
- Test: `backend/src/test/java/com/labex/labexagent/runtime/ContextWindowPolicyTest.java`
- Test: `backend/src/test/java/com/labex/labexagent/runtime/ContextWindowSupervisorTest.java`

**Data contract:**
- `inputCapacity = contextWindowTokens - maxTokens`.
- `reservedTokens` defaults to a bounded 10% safety margin.
- `softLimit = inputCapacity - reservedTokens`.
- Recent tail defaults to 25% of soft limit, clamped to 2,000-8,000 tokens.
- Full footprint includes system prompt, serialized tools, prompt-context blocks, and messages.

- [ ] Write failing policy and decision tests.
- [ ] Implement pure policy/decision classes without Spring dependencies.
- [ ] Extend context usage categories to expose tool-schema and reserve impact.
- [ ] Run focused tests.

### Task 3: Add turn-aware, audit-safe prompt pruning

**Files:**
- Create: `backend/src/main/java/com/labex/labexagent/runtime/TurnAwareContextPruner.java`
- Modify: `backend/src/main/java/com/labex/labexagent/runtime/AgentLoopEngine.java`
- Test: `backend/src/test/java/com/labex/labexagent/runtime/TurnAwareContextPrunerTest.java`
- Test: `backend/src/test/java/com/labex/labexagent/runtime/AgentLoopEngineContextBudgetTest.java`

**Data contract:**
- A turn starts at a user message and includes all following messages until the next user message.
- Recent turns are retained within the policy token budget; an oversized oldest retained turn may be split from the newest message backwards.
- Only eligible historical `[Tool ... result]` runtime messages are replaced.
- Write/patch/test/plan/permission/question/error results are protected from ordinary prune.
- The pruner returns a result with before/after tokens, retained-turn count, pruned-tool count, and checkpoint recommendation.

- [ ] Write failing turn-selection and protected-tool tests.
- [ ] Implement the pure pruner.
- [ ] Replace the fixed 300-character loop in `AgentLoopEngine` with supervisor + pruner decisions.
- [ ] If prune cannot return below the soft limit, invoke the existing deterministic checkpoint compactor before the model call.
- [ ] Preserve the existing provider-overflow recovery path.

### Task 4: Wire status and verify scoped behavior

**Files:**
- Modify: `backend/src/main/java/com/labex/labexagent/runtime/AgentLoopEngine.java`
- Modify: `backend/src/main/java/com/labex/labexagent/runtime/ContextUsageSnapshot.java` if required by the selected payload contract.
- Test: relevant runtime tests.

- [ ] Publish `CONTEXT_STATUS` strategy values that distinguish `NONE`, `TOOL_RESULT_PRUNE`, and `PROACTIVE_CHECKPOINT`.
- [ ] Keep existing `CHECKPOINT_COMPACTION` for provider-overflow recovery.
- [ ] Verify context-status payload remains backward-compatible for the frontend.
- [ ] Run focused backend tests, `mvn -f backend/pom.xml -DskipTests compile`, full `mvn -f backend/pom.xml test`, `npm run build`, and scoped `git diff --check`.