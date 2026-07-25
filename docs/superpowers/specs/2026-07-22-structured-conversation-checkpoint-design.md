# Structured Conversation Checkpoint Design

**Date:** 2026-07-22
**Status:** Approved by user

## Goal

Replace the overflow-only conversation summary in `AgentLoopEngine` with a bounded, deterministic checkpoint that preserves the information an agent needs to continue work after earlier turns are removed.

## Problem

The existing `compactWithLlmSummary(...)` name is misleading: it does not call a model. It keeps only aggregate tool/error counts and up to five early 150-character previews. It drops the original objective, current stage and plan, verification evidence, unresolved failures, and prior checkpoint facts.

## Decision

Add a package-local, dependency-free `ConversationCheckpointCompactor` in `com.labex.labexagent.runtime`. It will keep the latest three turns unchanged and replace older turns with a `conversation-checkpoint` message containing bounded sections:

1. task and acceptance;
2. execution state and plan progress;
3. changed/referenced files;
4. important findings and decisions;
5. verification evidence;
6. failures and unresolved work;
7. retrieval guidance.

The compactor will merge prior checkpoints by harvesting their retained bullets before it processes newer historical tool results. It never makes another LLM request, does not persist unverified facts into workspace memory, and avoids storing full tool output.

## Boundaries

- Scope is in-run overflow recovery only; durable workspace memory and database schema are unchanged.
- The agent retains existing proactive tool-result pruning and its latest-three-turn protection behavior.
- New output is capped and deduplicated so compression still lowers context size.
- The compactor only emits brief path, status, and diagnostic snippets already available in the same agent context.

## Verification

A focused unit test will prove that task/plan state, files, successful verification evidence, failures, and pre-existing checkpoint facts survive compression while recent turns remain identical and the total historical character count decreases. `AgentLoopEngineContextBudgetTest`, the new compactor test, and backend compile will be run after integration.
