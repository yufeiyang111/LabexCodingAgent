# 上下文压缩状态与富格式输出 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 Agent 思考/工具时间线上显示自动上下文压缩的实时和最终状态，并增强安全 Markdown 输出的结构化展示。

**Architecture:** 复用后端现有且已持久化的压缩生命周期事件。前端 reducer 将其归并为专属 `contextManagementEvents`，`CloudWorkspace.vue` 将思考、工具和上下文管理项目按 `_order` 合并渲染。Markdown 继续经白名单清洗，再添加受控提示块语法和统一样式；系统提示词仅增加简洁格式化规则。

**Tech Stack:** Vue 3、Vite、Node 内置测试、`marked`、`highlight.js`、Spring Boot 3、Java 17、JUnit 5。

## Global Constraints

- 不新增依赖，不开启原始 HTML，不削弱 Markdown 白名单清洗。
- 不改动压缩阈值、模型配置或既有后端事件名。
- 所有新增代码注释和用户可见文案使用中文。
- 不提交、不推送、不整理现有工作区的无关改动。

---

## File Structure

- Modify `frontend/src/composables/agentHistoryReducer.js` — 将持久化压缩事件归并为可更新的上下文管理卡片状态。
- Modify `frontend/src/composables/agentHistoryReducer.test.mjs` — 覆盖开始、失败、完成、回放兼容和 Token 释放量。
- Modify `frontend/src/views/CloudWorkspace.vue` — 实时 SSE 事件使用相同归并逻辑；混排并渲染上下文管理卡片；增加受控提示块预处理和样式。
- Modify `frontend/src/views/CloudWorkspace.test.mjs` or existing focused UI-source test — 验证专属卡片、Markdown 指令和安全边界。
- Modify `backend/src/main/java/com/labex/labexagent/prompt/LabexSystemPrompt.java` or current prompt assembly owner — 加入最小 Markdown 输出规范；仅在定位后修改。
- Modify/add backend focused test under `backend/src/test/java/com/labex/labexagent/prompt/` — 验证规范进入最终系统提示词，若已有同类测试。

### Task 1: 建立上下文管理事件状态模型

**Files:**
- Modify: `frontend/src/composables/agentHistoryReducer.js`
- Modify: `frontend/src/composables/agentHistoryReducer.test.mjs`

**Interfaces:**
- Produces: `message.contextManagementEvents: Array<{ kind: 'context-management', status: 'running'|'completed'|'warning', phase: 'compacting'|'pruned'|'fallback', strategy: string, tokensBefore: number|null, tokensAfter: number|null, reason: string, _order: number }>`.
- Consumes: persisted and live event names `COMPACTION_STARTED`, `CONTEXT_PRUNED`, `COMPACTION_COMPLETED`, `COMPACTION_FAILED`.

- [ ] Add failing reducer tests that assert a start event creates a `running` entry.
- [ ] Add failing reducer tests that assert failed/completed events update the outstanding entry and calculate released tokens from before/after values.
- [ ] Implement reducer helpers that normalize event payloads and update only the latest applicable entry.
- [ ] Run `npm test -- agentHistoryReducer.test.mjs` or the project test runner and verify the focused tests pass.

### Task 2: 在实时与历史时间线渲染专属压缩卡片

**Files:**
- Modify: `frontend/src/views/CloudWorkspace.vue`
- Test: relevant existing frontend source/component test or a new focused `.test.mjs`.

**Interfaces:**
- Consumes: `contextManagementEvents` created by reducer and `formatTokenCount` in `CloudWorkspace.vue`.
- Produces: merged items with `type: 'context'` and a visible card for running/completed/warning states.

- [ ] Add a failing focused test/source assertion for `context` merged items and the context-card labels.
- [ ] Route live SSE compression events through the same reducer semantics used by history replay, avoiding divergent state transitions.
- [ ] Extend `getMergedItems` with `context` items without changing ordering of thinking/tool items.
- [ ] Add accessible status text, pulsing running indicator, before/after token display, released-token display, and a readable fallback warning.
- [ ] Run frontend tests and inspect the template/build output for errors.

### Task 3: 强化安全 Markdown 与 Agent 格式化规范

**Files:**
- Modify: `frontend/src/views/CloudWorkspace.vue`
- Modify: prompt assembly owner after source inspection
- Test: focused frontend Markdown/source test; focused prompt test if one exists.

**Interfaces:**
- Produces: `normalizeSpecialMarkdownBlocks(text)` called before `marked.parse`, translating only documented `:::type` blocks to the already-supported safe callout syntax.
- Consumes: existing `sanitizeMarkdownHtml`, `enhanceMarkdownHtml`, `enhanceCallouts`, `handleMarkdownClick`.

- [ ] Add failing tests for allowed directive types, unsupported directives remaining plain text, code fences remaining untouched, and sanitizer retention.
- [ ] Implement directive normalization outside fenced code blocks; allow only note/tip/success/warning/important/error.
- [ ] Refine code, table, callout and context-card CSS for light/dark theme variables and narrow panels.
- [ ] Add concise system-prompt instructions for headings, GFM tables, language-tagged code fences and supported callouts; do not request raw HTML.
- [ ] Run frontend tests, `npm run build`, targeted backend prompt tests, then the appropriate broader Maven test gate.

### Task 4: 验收与回归

- [ ] Run `cd frontend; npm test`.
- [ ] Run `cd frontend; npm run build`.
- [ ] Run the focused Maven test selector for prompt tests and `cd backend; mvn test` if the focused gate is clean and time permits.
- [ ] Inspect `git diff --check` and scoped diffs; report unrelated dirty state without modifying it.
