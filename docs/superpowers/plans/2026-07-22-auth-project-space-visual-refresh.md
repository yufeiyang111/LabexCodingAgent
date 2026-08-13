# Authentication and Project Space Visual Refresh Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the project preview occupy the viewport and give the login/register entry point a warm, Claude-inspired but original product presentation.

**Architecture:** Keep all request, store, routing, and form submission code unchanged. Scope presentation changes to `CloudSpace.vue` and `Login.vue`; use a Node source-contract test because the repository has no Vue component-test runtime.

**Tech Stack:** Vue 3 SFCs, scoped CSS, Node.js test runner, Vite.

## Global Constraints

- Do not add dependencies or external assets.
- Do not copy Claude branding, page copy, or assets.
- Preserve the existing login/register request flow, validation, redirect, and API contract.
- Do not commit or alter unrelated working-tree changes.

---

### Task 1: Lock the visual-layout contract

**Files:**
- Create: `frontend/src/views/authProjectSpaceVisual.test.mjs`
- Modify: `frontend/src/views/CloudSpace.vue`
- Modify: `frontend/src/views/Login.vue`

**Interfaces:**
- Consumes: Vue SFC source text and Node's built-in test/assert modules.
- Produces: A focused regression check for the viewport and authentication structure.

- [ ] **Step 1: Write the failing source-contract test**
  - Assert `CloudSpace` uses `min-height: 100dvh` and does not retain the former 120px height subtraction.
  - Assert `Login` contains keyboard-accessible tab state and the new authentication/brand content containers.

- [ ] **Step 2: Run the focused test to confirm it fails**
  - Run: `node --test src/views/authProjectSpaceVisual.test.mjs`
  - Expected: fail before the presentation changes exist.

- [ ] **Step 3: Implement the project-space viewport fix**
  - Replace the fixed `calc(100vh - 120px)` shell height with a full dynamic viewport minimum height.
  - Keep the independent list and tree overflow areas intact.

- [ ] **Step 4: Implement the authentication presentation refresh**
  - Keep script logic unchanged.
  - Add semantic product/auth structure, accessible segment controls, CSS-only decoration, and responsive styles.

- [ ] **Step 5: Run the focused test to confirm it passes**
  - Run: `node --test src/views/authProjectSpaceVisual.test.mjs`
  - Expected: all assertions pass.

### Task 2: Verify the integrated frontend

**Files:**
- Verify: `frontend/src/views/CloudSpace.vue`
- Verify: `frontend/src/views/Login.vue`

- [ ] **Step 1: Run the full frontend test suite**
  - Run: `npm test`
  - Expected: existing and new Node tests pass.

- [ ] **Step 2: Run the production build**
  - Run: `npm run build`
  - Expected: Vite build and chunk budget check finish with exit code 0.

- [ ] **Step 3: Inspect the login screen at desktop and mobile widths**
  - Run Vite locally and capture `/login` at desktop and mobile viewports.
  - Expected: readable fields, visible focus states, no overflow, and no decorative content blocking the form.

- [ ] **Step 4: Review the scoped diff**
  - Run: `git diff -- frontend/src/views/CloudSpace.vue frontend/src/views/Login.vue frontend/src/views/authProjectSpaceVisual.test.mjs docs/superpowers/specs/2026-07-22-auth-project-space-visual-refresh-design.md docs/superpowers/plans/2026-07-22-auth-project-space-visual-refresh.md`
  - Expected: only planned documentation and presentation changes are present.
