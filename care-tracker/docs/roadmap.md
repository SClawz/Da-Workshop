# Roadmap

Checked items are done. Each phase ends with working, tested software and a short summary appended under "Phase log".

## Phase 0 — Plan
- [x] Architecture & plan (`architecture.md`)
- [x] Module, schedule-rule and expression spec (`spec-modules-and-scheduling.md`)
- [x] Initial ADRs (`decisions.md`)
- [ ] New GitHub repository created and this folder moved to its root (blocked: needs repo creation, see Q1)
- [ ] Plan approved and open questions answered

## Phase 1 — Core engines (pure TS, no UI)
- [ ] Spike: Temporal polyfill on Hermes (iOS + Android): correctness and performance
- [ ] Monorepo scaffold, tsconfig, lint, Vitest, CI with coverage gates
- [ ] `@care/core`: uuidv7, HLC, units + conversion, time wrapper
- [ ] `@care/core`: expression AST, type checker, evaluator, limits
- [ ] `@care/modules`: Zod schema + generated JSON Schema (drift check in CI)
- [ ] `@care/modules`: semantic validator + backward-compatibility checker
- [ ] `@care/modules`: interpreter (form models, defaults, effects, alerts) + migration ops
- [ ] Starter modules: Pet, Houseplant (+ profiles), Freshwater Aquarium, Sourdough, Medication, Home Maintenance, Custom
- [ ] `@care/scheduling`: interval, calendar, adaptive, meter, anyOf, modifiers (window/grace/early/bounds)
- [ ] `@care/scheduling`: status, matching, catch-up, notification planner, explain
- [ ] `@care/scheduling`: TZ matrix, DST, offline-gap and property tests
- [ ] `@care/insights`: trend series + moving average, thresholds (+ approach), adherence, streaks
- [ ] `@care/sync`: HLC merge, per-field LWW, shared test vectors
- [ ] CLI demo: 30-day agenda for a sample household

## Phase 2a — Offline app core
- [ ] Expo scaffold (dev builds), design tokens, navigation
- [ ] SQLite + Drizzle schema and migrations, repositories, personal space
- [ ] Add trackable flow (module → profile → attributes → tasks)
- [ ] Today / Agenda with one-tap complete and inline quick-log
- [ ] Trackables list, Trackable detail (attributes, tasks, history)
- [ ] Dynamic log entry sheet
- [ ] Spike: notification actions with app killed (iOS/Android)
- [ ] Local notifications: rolling window, bundling, actions, re-planning
- [ ] Entitlement flags (stub) + free-tier limits
- [ ] Settings: units, time zone behaviour; JSON/CSV export

## Phase 2b — Offline app completeness
- [ ] Custom module builder v1
- [ ] Insights tab: trend chart with safe-range band, accessible text alternative
- [ ] Optional biometric app lock
- [ ] Accessibility pass #1, performance budget measured
- [ ] Internal TestFlight / Play internal build

## Phase 3 — Accounts & sync
- [ ] Auth: Apple, Google, email magic link
- [ ] Postgres schema, RLS, `apply_changes`, `server_seq`
- [ ] Sync client: outbox, push, pull, Realtime poke, local→account merge
- [ ] Photo upload queue, EXIF stripping, signed URLs
- [ ] Health-data consent flow; legal review ⚠️
- [ ] Account deletion (in-app + web URL); full export

## Phase 4 — Monetization
- [ ] RevenueCat products: monthly, annual, lifetime
- [ ] Paywall (price/term clarity, restore, Terms/Privacy links)
- [ ] Webhook → `entitlements`; server-side enforcement
- [ ] Downgrade behaviour (read-only beyond limit, sync paused)

## Phase 5 — Shared spaces
- [ ] Invites (link/QR), roles, member management
- [ ] Activity feed, "already done", double-completion detection
- [ ] Expo push, privacy-safe payloads, per-member prefs
- [ ] Missed-task alerts (pg_cron + edge function)

## Phase 6 — Smart scheduling & insights v2
- [ ] Weather proxy edge function + coarse location flow ⚠️
- [ ] Adaptive rules live with "why this date" explanations
- [ ] Threshold-approach warnings, weekly digest
- [ ] PDF/CSV reports
- [ ] Correlations (flagged), computed fields

## Phase 7 — Polish & launch
- [ ] Widgets (snapshot-file based)
- [ ] Onboarding
- [ ] Accessibility pass #2, performance sign-off
- [ ] Store assets, privacy labels, Data safety, Health apps declaration ⚠️
- [ ] Terms & Privacy Policy

## Phase log
- **2026-10-10 — Phase 0 drafted.** Plan, spec and ADRs written and awaiting review. The new repository couldn't be created from
  the session (GitHub 403), so the docs live in `care-tracker/` on branch `ccr-b1304701-y6yy5e` of `Da-Workshop` for now.
