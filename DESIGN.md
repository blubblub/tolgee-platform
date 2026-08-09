# Design

## Source of truth

- Status: Active
- Last refreshed: 2026-08-09
- Primary product surfaces: Upstream Tolgee UI, plus the Blub Blub binary-assets table and translation pipeline.
- Evidence reviewed: `README.md`, `AGENTS.md`, `webapp/src/ThemeProvider.tsx`, `webapp/src/constants/translationStates.tsx`, `webapp/src/views/projects/assets/AssetLocalizedFiles.tsx`, `webapp/src/views/projects/assets/BinaryAssetPreview.tsx`, and production screenshots.

## Brand

- Personality: Practical, calm, and workflow-focused.
- Trust signals: Explicit state, reversible actions, visible progress, and consistent permission gating.
- Avoid: Decorative UI that competes with localization work or hides state behind icon-only meaning.

## Product goals

- Goals: Make localization state and the next useful action immediately legible.
- Non-goals: Re-theme upstream Tolgee or introduce a fork-specific component system.
- Success signals: Users can identify the active file, its review state, and its adjacent actions without leaving the table.

## Personas and jobs

- Primary personas: Translators, reviewers, localization managers, and project maintainers.
- User jobs: Upload or record localized media, compare previews, choose a final version, and confirm that final.
- Key contexts of use: Dense desktop tables with many languages and repeated media controls.

## Information architecture

- Primary navigation: Preserve upstream Tolgee project navigation.
- Core routes/screens: `/projects/{id}/assets` and `/projects/{id}/assets/{assetId}/translations/{languageId}`.
- Content hierarchy: Language → file/review status → Preview → file metadata/transcript → Final → actions.

## Design principles

- Keep an action beside the object it affects; recording controls follow the corresponding media preview.
- Show workflow state, not implementation state; a current unconfirmed final reads “Needs Review,” not “CURRENT.”
- Tradeoffs: Dense tables may scroll horizontally rather than hiding controls or truncating workflow state.

## Visual language

- Color: Use existing MUI theme tokens; warning for work needing attention and success for confirmed work.
- Typography: Use existing MUI body, caption, and chip typography.
- Spacing/layout rhythm: Reuse the table's compact `0.5` action gap and small controls.
- Shape/radius/elevation: Preserve existing MUI component styling.
- Motion: Existing progress indicators only; no decorative motion.
- Imagery/iconography: Reuse Untitled UI icons already present in the asset table.

## Components

- Existing components to reuse: MUI `Table`, `Chip`, `Tooltip`, `IconButton`, and `BinaryAssetPreview`.
- New/changed components: No new component; adjust `AssetLocalizedFiles` composition only.
- Variants and states: Missing = neutral, outdated = warning, current/unconfirmed = “Needs Review” warning, current/confirmed = “Reviewed” success.
- Token/component ownership: `ThemeProvider.tsx` and upstream MUI own tokens; feature code selects semantic variants.

## Accessibility

- Target standard: Preserve upstream semantic table and MUI keyboard behavior.
- Keyboard/focus behavior: Every icon action remains a native focusable button with a tooltip label.
- Contrast/readability: Use theme warning/success chip variants instead of raw colors.
- Screen-reader semantics: Keep language cells as row headers and status text explicit.
- Reduced motion and sensory considerations: No new animation or color-only status; every color has a text label.

## Responsive behavior

- Supported breakpoints/devices: Existing webapp browser support.
- Layout adaptations: The asset table keeps its horizontal overflow container on narrow viewports.
- Touch/hover differences: Actions remain buttons; tooltips supplement rather than replace visible state text.

## Interaction states

- Loading: Keep the existing row-scoped progress indicators.
- Empty: Keep neutral dashes and existing empty-state copy.
- Error: Keep inline alert text and retryable recorder state.
- Success: A confirmed current final displays “Reviewed” with success styling.
- Disabled: Preserve permission and in-flight mutation guards.
- Offline/slow network, if applicable: Preserve the current state until the server mutation succeeds and refetches.

## Content voice

- Tone: Short and operational.
- Terminology: “Preview,” “Final,” “Needs Review,” and “Reviewed.”
- Microcopy rules: Label the user's workflow state; avoid backend enum names where they are not actionable.

## Implementation constraints

- Framework/styling system: React, Material UI, and existing Tolgee hooks/components.
- Design-token constraints: No raw feature colors when semantic palette variants exist.
- Performance constraints: Avoid additional per-row API requests.
- Compatibility constraints: Fork-only translation keys live in `webapp/tolgee.prod.d.ts`; generated `en.json` is not edited.
- Test/screenshot expectations: Component tests assert semantic label/color and DOM order; production smoke verifies Preview/Final placement.

## Open questions

- [ ] None for the current asset-table scope.
