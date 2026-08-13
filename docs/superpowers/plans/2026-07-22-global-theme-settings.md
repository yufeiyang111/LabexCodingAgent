# Global Theme Settings Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver a componentized, persistent global theme settings drawer matching the supplied reference structure across every frontend route.

**Architecture:** A pure theme-preference module owns schema validation, legacy migration, storage, and document attributes. A Pinia store makes the preferences reactive and handles panel state. Reusable components render the drawer; global Sass tokens and limited view-level semantic variables apply it consistently to login, project list, and workspace.

**Tech Stack:** Vue 3, Pinia, Sass, Element Plus, Node built-in test runner, Vite.

## Global Constraints

- Do not add dependencies or change backend/database/authentication code.
- Use `labex-theme-preferences.v1` for non-sensitive browser-local settings.
- Keep `data-theme="dark"` compatible with existing workbench dark-mode selectors.
- Use semantic CSS variables instead of adding a new page-local theme state.
- Do not commit changes unless explicitly requested.

---

### Task 1: Theme preference domain and persistence

**Files:**
- Create: `frontend/src/theme/theme-preferences.js`
- Create: `frontend/src/theme/theme-preferences.test.mjs`
- Create: `frontend/src/theme/theme-options.js`

**Interfaces:**
- Produces `DEFAULT_THEME_PREFERENCES`, `loadThemePreferences`, `saveThemePreferences`, `normalizeThemePreferences`, `applyThemePreferences`, and `resolveThemeMode`.

- [ ] Write a failing test for defaults, invalid storage, legacy migration, persistence, reset values, and root attributes.
- [ ] Run `node --test src/theme/theme-preferences.test.mjs` and confirm it fails before implementation.
- [ ] Implement the minimal pure preference module and option metadata.
- [ ] Re-run the focused unit test.

### Task 2: Reactive global theme store

**Files:**
- Create: `frontend/src/stores/theme.js`
- Modify: `frontend/src/main.js`

**Interfaces:**
- Consumes the pure preference functions.
- Produces `useThemeStore` with `initialize`, `updatePreference`, `resetPreferences`, `openSettings`, `closeSettings`, and `toggleLightDark`.

- [ ] Add a failing test for the store-facing preference behavior where feasible without a DOM framework.
- [ ] Initialize the store before the application mounts to prevent a default-theme flash.
- [ ] Subscribe to OS color-scheme changes only while system mode is selected.
- [ ] Run focused tests.

### Task 3: Reusable settings drawer and launcher

**Files:**
- Create: `frontend/src/components/theme/ThemeSettingsDrawer.vue`
- Create: `frontend/src/components/theme/ThemeSettingsLauncher.vue`
- Create: `frontend/src/components/theme/ThemeSettingsSection.vue`
- Create: `frontend/src/components/theme/ThemeChoiceCard.vue`
- Create: `frontend/src/components/theme/ThemePreview.vue`
- Modify: `frontend/src/App.vue`

**Interfaces:**
- Components consume `useThemeStore` and option metadata only; they do not directly write local storage.

- [ ] Add static component regression assertions for all required sections and the reset control.
- [ ] Implement the right-side, scrollable drawer with sticky header/footer, keyboard close, selected indicators, and `aria-pressed` choice controls.
- [ ] Mount launcher and drawer globally in `App.vue`.
- [ ] Run focused frontend tests.

### Task 4: Global tokens and route integration

**Files:**
- Create: `frontend/src/styles/theme.scss`
- Modify: `frontend/src/main.js`
- Modify: `frontend/src/styles/global.scss`
- Modify: `frontend/src/styles/wabi-sabi.scss`
- Modify: `frontend/src/views/Login.vue`
- Modify: `frontend/src/views/CloudSpace.vue`
- Modify: `frontend/src/views/CloudWorkspace.vue`

**Interfaces:**
- Root document attributes from Task 1 control CSS variables declared in `theme.scss`.

- [ ] Replace the old workspace-only settings popup and separate local-storage keys with global store calls.
- [ ] Apply semantic tokens to the project and login shells, Element Plus surfaces, and workbench shell.
- [ ] Map density, radius, sidebar, layout, content width, and direction attributes to non-destructive layout styling.
- [ ] Run component and domain tests.

### Task 5: Full verification and visual smoke test

**Files:**
- Modify any prior files only to resolve verified issues.

- [ ] Run `npm test` in `frontend`.
- [ ] Run `npm run build` in `frontend`.
- [ ] Start the frontend if required and inspect login, projects, and workspace in light and dark modes.
- [ ] Hard-refresh after changing a preference and confirm it remains selected.
