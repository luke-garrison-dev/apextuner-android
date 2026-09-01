# ApexTuner 1.0.9 — UI/UX, accessibility and adaptive-layout audit

**Audit date:** 2026-08-28  
**Application ID:** `com.apextuner.app`  
**Version:** `1.0.9` / `versionCode 9`

## Scope boundary

This release pass is intentionally limited to interface, usability, accessibility, adaptive layout and visual identity. No feature semantics, optimizer algorithms, billing architecture, permissions policy, persistence model or privileged-operation behavior was intentionally changed by this UI pass.

## Implemented interface improvements

- Top-level navigation adapts to the actual app-window width: compact windows retain Material 3 bottom navigation, while widths at or above 600dp use a Navigation Rail.
- Primary feature content is centered and width-limited on large windows so tablets, foldables and desktop/freeform windows do not stretch controls or text across the entire display.
- Shared responsive horizontal padding scales from compact phones to expanded layouts.
- Metric rows switch from two-column label/value presentation to a stacked form before narrow layouts become cramped, improving readability with large font scaling.
- Filter/control groups that can expand horizontally use wrapping layouts rather than relying on clipping or overly compressed labels.
- Tool surfaces and dense feature pages use adaptive spacing and width constraints while keeping existing actions and business logic unchanged.
- Bottom-navigation labels degrade gracefully on very narrow windows, while icons retain accessible content descriptions.
- Checkbox/radio semantics avoid duplicate TalkBack actions and preserve clear selected-state behavior.
- Touch targets for widget/compact controls are guarded at 48dp or larger where interactive.
- Floating monitor positioning is constrained to the current screen bounds so it cannot be dragged permanently off-screen.
- Forced portrait orientation was removed so the app can adapt to landscape, tablets, foldables and resizable windows.

## Visual-design improvements

- Introduced an ApexTuner-specific Material 3 visual language instead of a generic default appearance.
- Refined dark/light palettes around technical cyan, violet and performance-green accents with explicit surface, outline and error roles.
- Added a consistent rounded shape system (8/12/18/26/32dp) for hierarchy without excessive decoration.
- Refined typography hierarchy with a 12sp minimum label/body-small baseline, improved line heights and stronger title weights.
- Added subtle application-shell depth through surface/background treatment while preserving contrast and legibility.
- Reworked launcher/adaptive/round icon assets around a layered Apex performance mark with dimensional/extruded treatment, technical dial cues and cyan/violet/green identity.
- Updated widget/floating-monitor presentation to use the same product identity.

## Accessibility and screen-size safeguards

The source validator now checks interface-specific invariants including:

- adaptive navigation breakpoint and Navigation Rail presence;
- bounded content width for expanded screens;
- responsive metric-row stacking;
- wrapping control groups;
- 48dp interactive widget targets;
- accessibility semantics for selection controls;
- floating-overlay screen-bound clamping;
- minimum 12sp typography definitions;
- absence of a forced screen orientation.

## Executed regression evidence

Against the final 1.0.9 source tree:

- Project/UI/source validator: **PASS — 1,144 checks, 39 XML files, 11 main manifests**.
- Pure JVM suite recompiled from the current production/test sources: **PASS — 55 test methods across 18 test classes**.
- Production-domain harness recompiled from the current sources: **PASS — 50,015 checks**.

These JVM/domain gates verify that the UI-only release pass did not regress the covered optimization, entitlement, telemetry, firewall-resource, cleaner, recommendation, scheduling or geometry logic.

## Android-device validation boundary

The current sandbox does not contain a complete Android SDK/AGP dependency cache and cannot fetch the missing binary toolchain from Gradle services. Therefore this report does not falsely claim a local `assembleDebug`, Android Lint, emulator instrumentation or physical-device visual benchmark. The project includes Android CI configuration for the genuine SDK-capable build gates; release-significant visual/accessibility checks should additionally be exercised on phones, tablets/foldables and with enlarged font/display settings.
