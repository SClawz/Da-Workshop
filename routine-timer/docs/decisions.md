# Architecture Decision Records

Lightweight ADRs. Status values: **Proposed** (awaiting approval), **Accepted**, **Superseded by ADR-n**, **Withdrawn**.
Add new records at the bottom. Never edit accepted ones; supersede them instead.

> **Revision 1 (care tracker) ADRs were withdrawn on 2026-10-10** when the product changed to a routine tracker plus multi-timer.
> None had been accepted. They are in git history (commit `88e6501`). Ideas carried forward are restated below.

---

### ADR-001 — Monorepo with pure engine packages
- **Status:** Proposed (2026-10-10)
- **Decision:** A pnpm workspace containing:
  - `packages/{core,timer,schedule,templates,insights,notify}`: pure TS, no React/RN/I/O, time injected
  - `modules/timer-native`: an Expo local native module
  - `apps/mobile`: the Expo app
- **Consequences:** Engines are reusable in a future watch app, widget or web app, and fast to test.

### ADR-002 — React Native + Expo + TypeScript strict, with a local native module
- **Status:** Proposed
- **Decision:**
  - Expo dev builds, expo-router and EAS.
  - Native timer features (AlarmKit, Live Activities, Android exact alarms, chronometer notifications, full-screen intent) are
    written in Swift/Kotlin behind a narrow Expo Modules API.
- **Consequences:** We keep the Expo workflow and own a small amount of native code. That code is the riskiest part, so it's spiked first in Phase 2.

### ADR-003 — Temporal (polyfilled) for date/time
- **Status:** Proposed, pending the Phase 1 Hermes spike. Fallback: Luxon behind the same `@rt/core/time` wrapper.

### ADR-004 — Zod as schema source of truth; JSON Schema generated for templates
- **Status:** Proposed
- **Consequences:** No runtime code generation on device. CI checks the generated schema for drift.

### ADR-005 — Timers are event-sourced; state is projected, never ticked
- **Status:** Proposed
- **Context:** JS doesn't run in the background, apps get killed, and phones reboot.
- **Decision:**
  - A running timer is an immutable plan snapshot plus an append-only list of timestamped events.
  - `project(plan, events, now)` computes the state, and `boundaries()` computes future instants that are handed to the OS.
  - Clock jumps are recorded as correction events.
- **Consequences:** Timers survive kills and reboots, are fully unit-testable, sync trivially later, and support after-the-fact edits.

### ADR-006 — One record type: Session
- **Status:** Proposed
- **Decision:** Every routine run, timed step and standalone timer run is a `Session`, which carries a plan snapshot, events,
  step results and field values. Visualization runs only on sessions.
- **Consequences:** One insights engine serves both halves of the product. History stays correct after presets are edited.

### ADR-007 — Templates are copied into user items, not referenced
- **Status:** Proposed
- **Decision:** `instantiate(template, params)` produces an ordinary, editable routine or preset. Template versioning only matters
  for the catalog itself.
- **Consequences:** Revision 1's module-version pinning and migration machinery is removed. Template updates don't
  propagate to existing items (acceptable; we can offer an "update from template" action later).

### ADR-008 — Routine occurrences are derived, not materialized
- **Status:** Proposed (carried from rev 1)

### ADR-009 — Schedule rules: interval, calendar, quota + window/grace/bounds modifiers
- **Status:** Proposed
- **Decision:** Adaptive, weather, meter and anyOf rules are dropped from MVP. `quota` (time or count goals per period) is new.
  The rule format stays a discriminated union, so new kinds are additive.

### ADR-010 — Notification planner with a shared budget and fixed priorities
- **Status:** Proposed
- **Decision:** One planner serves timers and routines under the iOS 64-pending limit. Priority order:
  1. timer final alarms
  2. sequence cues
  3. group starts
  4. routine reminders

  Final alarms are never dropped. AlarmKit is used on iOS 26+ for final alarms.

### ADR-011 — No foreground service on Android
- **Status:** Proposed
- **Decision:** Use exact alarms plus a native chronometer ongoing notification instead of a foreground service.
- **Consequences:** No Android 14 foreground-service-type review and better battery life. The in-app ticker runs only while visible.

### ADR-012 — Charts with react-native-svg + d3 helpers
- **Status:** Proposed
- **Decision:** Hand-built chart components: stacked bar, line, calendar heatmap, weekday×hour heatmap, histogram, ring, sparkline.
  The insights engine returns chart-ready series plus accessible summaries.

### ADR-013 — Offline-only MVP, sync-ready schema
- **Status:** Proposed
- **Decision:** No accounts or server until Phase 7. Local tables still carry UUIDv7, HLC, field clocks and tombstones. The rev 1
  sync design (per-field LWW, outbox, `server_seq` pull, Supabase) is retained for Phase 7.

### ADR-014 — Reliability is never paywalled
- **Status:** Proposed
- **Decision:** All timer kinds, unlimited concurrent timers, alarms, Live Activities and notification actions are free. Premium is
  depth: charts beyond 7 days, unlimited saved presets and routines, custom templates, the extended library, shareable summaries,
  and later sync.

### ADR-015 — Default tooling
- **Status:** Proposed (small decision; proceeding unless you object)
- **Decision:**
  - pnpm and Vitest + fast-check
  - jest-expo + RNTL, plus **Maestro** for device-level timer reliability flows
  - ESLint (eslint-config-expo) + Prettier and GitHub Actions
  - Conventional Commits
  - Sentry (opt-out, scrubbed)
