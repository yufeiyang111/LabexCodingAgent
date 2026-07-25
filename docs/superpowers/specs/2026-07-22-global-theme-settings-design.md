# Global Theme Settings Design

**Date:** 2026-07-22

## Goal

Replace the workspace-only appearance popup with a reusable global theme settings drawer that applies to the login page, project list, and workspace. Preferences must survive reloads in the same browser and be applied before the Vue application is mounted.

## Constraints

- Use the existing Vue 3, Pinia, Sass, and Element Plus stack. Do not add dependencies.
- Keep theme state, persistence, option metadata, UI, and global CSS in separate files.
- Store only non-sensitive UI preferences in browser local storage.
- Use safe defaults whenever persisted data is malformed or stale.
- Preserve the existing workbench dark-mode compatibility selector, `data-theme="dark"`.
- Do not change authentication, backend APIs, or database schema.

## Preferences

The versioned `labex-theme-preferences.v1` object contains:

- mode: `system`, `light`, or `dark`
- colorPreset: ten named visual presets
- fontFamily: `auto`, `sans`, or `serif`
- radius: `auto`, `0`, `0.3`, `0.5`, `0.75`, or `1.0`
- density: `compact`, `default`, `relaxed`, or `spacious`
- sidebar: `embedded`, `floating`, or `sidebar`
- layout: `default`, `compact`, or `fullscreen`
- contentWidth: `full` or `centered`
- direction: `ltr` or `rtl`

Legacy workspace preferences are read once as a migration source when no versioned value exists. Subsequent writes only use the new single key.

## Architecture

- `src/theme/theme-preferences.js` is a pure, testable module for defaults, validation, migration, persistence, and root-document attributes.
- `src/stores/theme.js` exposes reactive Pinia state, the system theme listener, and UI open/close state.
- `src/theme/theme-options.js` contains the drawer labels and preview metadata.
- `src/components/theme/*` renders the reusable drawer and its small presentation components.
- `src/styles/theme.scss` owns runtime design tokens and app-wide compatibility overrides.
- `App.vue` mounts a global launcher and drawer. Existing workspace controls open the same drawer.

## UI

The drawer opens on the right over a dimmed page, has a sticky header and bottom reset action, and scrolls between them. It follows the supplied reference images: grouped labeled settings, visual cards, selected check mark, focus state, and compact dark styling. The actual colors follow the selected appearance, including when light mode is selected.

## Global Application Mapping

Root data attributes and CSS custom properties affect fonts, radius, colors, density, page content width, and text direction globally. The workspace maps sidebar/layout options to safe visual layout adjustments rather than hiding task-critical panes. The project page maps the same settings to its list/detail shell. The login page receives the same color, font, spacing, and radius tokens.

## Accessibility and Reliability

All choices are native buttons with `aria-pressed`, a visible focus ring, descriptive labels, and an Escape-key close handler. Root preference application tolerates unavailable browser APIs so the pure module remains testable. Reset changes the page immediately and persists the default value.

## Verification

- Unit test defaults, invalid persisted values, legacy migration, document attributes, persistence, reset, and system-color resolution.
- Run `npm test` and `npm run build` from `frontend`.
- Manually inspect the three routes and verify a changed setting survives a hard refresh.
