# Live Agent Stream And Center Terminal Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver immediate Agent SSE rendering and a resizable terminal restricted to the center editor column.

**Architecture:** Forward provider deltas without artificial throttling, let FINAL reconcile the accumulated answer, and update Vue message state immediately. Wrap the editor and terminal in a center column whose terminal height is controlled by pointer dragging.

**Tech Stack:** Java 21, Spring Boot SseEmitter, Vue 3 Composition API, xterm.js, Node test runner.

## Global Constraints
- Preserve current SSE event names and replay support.
- Preserve terminal WebSocket protocol.
- Add no dependencies.
- Do not modify or expose secrets.
- Do not commit or stage changes.

---

### Task 1: Backend live delta forwarding

**Files:**
- Modify: `backend/src/main/java/com/labex/labexagent/runtime/AgentLoopEngine.java`
- Test: `backend/src/test/java/com/labex/labexagent/runtime/AgentLoopEngineStreamingContractTest.java`

- [ ] Add a failing contract test requiring TEXT_DELTA to send FINAL_DELTA and forbidding Thread.sleep in the stream callback.
- [ ] Run the targeted Maven test and confirm failure.
- [ ] Forward provider text deltas and raw thinking deltas directly to SSE.
- [ ] Run targeted backend tests.

### Task 2: Immediate frontend event rendering

**Files:**
- Modify: `frontend/src/views/CloudWorkspace.vue`
- Test: `frontend/src/views/agentStreamIntegration.test.mjs`

- [ ] Add failing assertions requiring direct thinking display updates and live FINAL_DELTA handling.
- [ ] Run the test and confirm failure.
- [ ] Remove the one-second thinking reveal timer and update scroll/render scheduling on each meaningful event.
- [ ] Run frontend targeted tests.

### Task 3: Center-only resizable terminal

**Files:**
- Modify: `frontend/src/views/CloudWorkspace.vue`
- Test: `frontend/src/views/terminalPanelResize.test.mjs`

- [ ] Add failing layout assertions for workspace-center and terminal resize handle.
- [ ] Run the test and confirm failure.
- [ ] Move the terminal inside the center column and add pointer-based height resizing with bounds.
- [ ] Fit all terminals after opening and resizing.
- [ ] Run the terminal test.

### Task 4: End-to-end verification

- [ ] Run `npm test` and `npm run build` in `frontend`.
- [ ] Run targeted backend tests and `mvn -q -DskipTests package` in `backend`.
- [ ] Restart the backend on port 8080.
- [ ] Verify in the real workspace that SSE content updates without refresh and the terminal only occupies the center column.