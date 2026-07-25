# LabexAgent Authentication and Project Space Visual Refresh Design

## Goal

Remove the unused viewport space beneath the project preview and refresh the shared login/register screen with a warm, focused visual language inspired by Claude's restraint and editorial spacing, without copying Claude branding or changing LabexAgent behavior.

## Scope

- Make the CloudSpace shell fill the available viewport on desktop and mobile.
- Redesign the login/register view using existing Vue, CSS, and `AppIcon` only.
- Preserve routes, credentials, validation, API calls, loading states, and redirects.

## Visual direction

The UI uses a warm paper background, ink-like text, one restrained coral accent, generous spacing, and rounded but not overly glossy surfaces. The login screen has a product-story panel and a focused authentication card. Decorative shapes are CSS-only, so there are no new assets, dependencies, or copyright concerns.

## Layout

### Project space

`CloudSpace.vue` owns the full browser viewport through `min-height: 100dvh`; it does not subtract a magic pixel height. Its project list remains independently scrollable, while the file-tree panel continues to flex within the right side.

### Authentication

A desktop layout has a warm brand/story panel on the left and an authentication panel on the right. The auth panel shows a small product mark, contextual heading, login/register segment control, the existing form fields, and a compact privacy note. Below 900px the presentation becomes a single-column layout and removes nonessential decorative art.

## Accessibility and interaction

- Existing native labels and inputs remain intact.
- The active tab gets `aria-pressed`.
- Focus-visible styles are high contrast and keyboard usable.
- No authentication behavior or error copy changes.

## Verification

- Add a Node test that locks the viewport-height contract and key authentication accessibility/structure hooks.
- Run the focused test, the complete existing frontend test suite, and `npm run build`.
- Start Vite and capture desktop and mobile screenshots of `/login`; project-space visual inspection requires an authenticated session and is covered by the height contract plus build validation.
