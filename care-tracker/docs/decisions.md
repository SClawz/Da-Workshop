# Architecture Decision Records

Lightweight ADRs. Status values: **Proposed** (awaiting approval), **Accepted**, **Superseded by ADR-n**.
Each record has the context, the decision, and its consequences. Add new records at the bottom and never edit accepted ones;
supersede them instead.

---

### ADR-001 — Monorepo with pure engine packages
- **Status:** Proposed (2026-10-10)
- **Context:** The brief requires the module, scheduling and insights engines to be reusable later (web app, widgets,
  server) and heavily tested.
- **Decision:** A pnpm workspace with `packages/{core,modules,scheduling,insights,sync}` (pure TS, no React/RN/I/O,
  time injected), `apps/mobile` (Expo), and `supabase/`.
- **Consequences:** Clear boundaries and fast unit tests. Some Metro/pnpm config friction, solvable with `node-linker=hoisted`
  if needed.

### ADR-002 — React Native + Expo + TypeScript strict
- **Status:** Proposed
- **Context:** Cross-platform mobile, small team, shared TS with edge functions and a future web app.
- **Decision:** Expo (latest stable SDK at scaffold time) with dev builds, expo-router, EAS Build/Submit/Update.
  TS `strict`, `noUncheckedIndexedAccess`, `exactOptionalPropertyTypes`.
- **Consequences:** One codebase. Native features need config plugins. Expo Go can't be used once RevenueCat is added.

### ADR-003 — Temporal (polyfilled) for all date/time logic
- **Status:** Proposed, pending Phase 1 Hermes spike
- **Context:** Scheduling must distinguish wall-clock from exact time and handle DST and time zones correctly.
- **Decision:** Use the Temporal API via `temporal-polyfill`, accessed only through `@care/core/time`.
  Fallback: Luxon behind the same wrapper.
- **Consequences:** Correct semantics and future native support. Adds roughly 20 kB gz, and performance on Hermes has to be verified.

### ADR-004 — Zod is the schema source of truth; JSON Schema is generated
- **Status:** Proposed
- **Context:** The brief asks for JSON Schema validation. The standard validator (Ajv) relies on runtime code generation, and
  JSON Schema can't express semantic checks.
- **Decision:** Define module and rule schemas in Zod. Generate `module.schema.json` for authoring and tooling. Run a second semantic
  validation pass (refs, types, uniqueness, backward compatibility) in `@care/modules`.
- **Consequences:** No `eval`/`new Function` on device. TS types come for free. The JSON Schema artifact must be regenerated in CI
  (checked for drift).

### ADR-005 — Declarative rules and a typed expression AST; no code evaluation
- **Status:** Proposed
- **Context:** Adaptive schedules, effects and alerts need conditions, and remote modules must be safe and store-compliant.
- **Decision:** A JSON AST with a fixed operator set, whitelisted refs, static type-checking against the module, size/depth limits,
  and null-propagating semantics. There is no text syntax in MVP.
- **Consequences:** Safe and explainable, and the builder can emit it directly. Less expressive than a scripting language, which is intentional.

### ADR-006 — Every trackable belongs to a space
- **Status:** Proposed
- **Decision:** Signed-out users get an implicit local *personal space*. Sharing means adding members to a space.
- **Consequences:** No data migration when a user signs up or starts sharing. RLS is keyed on `space_id` everywhere.

### ADR-007 — Sync: per-field LWW with HLC, append-only logs, server-sequence pull
- **Status:** Proposed (see open question Q3: custom vs PowerSync)
- **Decision:** Outbox of field-level patches. Push goes through an idempotent `apply_changes` RPC under RLS. Pull uses a `server_seq` cursor.
  Realtime is used only as a poke. The same merge function exists in TS and SQL, verified by shared test vectors.
- **Consequences:** A small amount of sync code to own, plus a dependency-free conflict model suited to this app. Double completions
  are surfaced, never merged away.

### ADR-008 — Occurrences are derived, not materialized
- **Status:** Proposed
- **Decision:** Only log entries (done/skipped) are stored. Expected and missed occurrences are computed by the engine. A local
  `task_state` cache is kept for speed, and `next_due_at`/`alert_at` are synced as derived values for server alerts.
- **Consequences:** Small sync surface, and rule edits are consistent. The engine must be fast and deterministic.

### ADR-009 — Append-only module evolution
- **Status:** Proposed
- **Decision:** Field, task and enum IDs are immutable. Removal means deprecation. Migrations touch attributes and task config/schedules only,
  never log entries. Trackables pin `module_version` and upgrade lazily.
- **Consequences:** History is always readable. Definitions accumulate deprecated fields over time, which is acceptable.

### ADR-010 — Custom module builder is free
- **Status:** Proposed (open question Q6)
- **Decision:** Fields, tasks and non-adaptive schedules are free. Adaptive rules and insight configuration in the builder are premium.
- **Consequences:** The core promise ("track anything") is free and costs us nothing to run. The trackable limit remains the main upgrade lever.

### ADR-011 — Local notifications on a rolling, bundled window
- **Status:** Proposed
- **Decision:** Plan the next 7 days, bundle occurrences that share a minute, cap at the platform budget (iOS 60 / Android 200),
  re-plan on foreground, log, edit, sync and background task, and diff against what's already scheduled.
- **Consequences:** Stays within OS limits. Reminders may stop if the app isn't opened for a long time, mitigated by a final
  "open the app" notification.

### ADR-012 — Charts with react-native-svg + d3-scale/shape; PDF via expo-print
- **Status:** Proposed
- **Decision:** Hand-built chart components on SVG. Report HTML/SVG/CSV is built in pure TS (`@care/insights`), and the OS renders
  the PDF via `expo-print`.
- **Consequences:** Minimal dependencies and one chart renderer shared by screen and report. More chart code to write ourselves.

### ADR-013 — Generic extensions: multi-instance tasks, counters/effects/actions/alerts, meter and anyOf rules
- **Status:** Proposed — **flagged** per the brief: the starter modules (pet medications, medication refills, future vehicle
  maintenance) couldn't be expressed without engine changes.
- **Decision:** Add these as generic capabilities of the module format and engines in Phase 1.
- **Consequences:** Slightly larger Phase 1. Removes the need for category-specific code later.

### ADR-014 — Attribute-based adjustments are free (applied at setup); ongoing adaptation is premium
- **Status:** Proposed
- **Context:** "A larger pot means a longer interval" is basic correctness, but weather/season/log-driven adaptation is a running-cost premium feature.
- **Decision:** For free users, adaptive adjusters that reference only `attr.*`/care-profile values are evaluated once when the
  task is created (or when attributes change) to seed the base interval. Premium users get continuous evaluation of all adjusters.
- **Consequences:** Free schedules are sensible out of the box, and premium has a clear "it adapts" story.

### ADR-015 — Default tooling
- **Status:** Proposed (small decision; I'll proceed unless you object)
- **Decision:** pnpm, Vitest + fast-check, jest-expo + RNTL for the app, ESLint (eslint-config-expo) + Prettier, GitHub Actions CI,
  Conventional Commits, UUIDv7 IDs, Sentry with PII scrubbing (opt-out).
