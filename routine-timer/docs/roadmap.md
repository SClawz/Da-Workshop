# Roadmap

Checked items are done. Each phase ends with working, tested software and a short summary under "Phase log".

## Phase 0 — Plan
- [x] Rev 1 plan (care tracker)
- [x] Rev 2 plan: routine tracker + multi-timer (`architecture.md`)
- [x] Spec: timers, routines, schedules, templates, insights (`spec-timers-routines-templates.md`)
- [x] ADRs (`decisions.md`)
- [ ] New GitHub repository created and this folder moved to its root (blocked: see Q1)
- [ ] Plan approved, open questions answered

## Phase 1 — Core engines (pure TS)
- [ ] Spike: Temporal polyfill on Hermes
- [ ] Monorepo scaffold, CI, coverage gates
- [ ] `@rt/core`: ids, HLC, time wrapper, durations
- [ ] `@rt/timer`: countdown, stopwatch (laps, cap), sequence (nested repeats, manual phases), group (finish together)
- [ ] `@rt/timer`: projection, boundaries, adjust, clock correction, after-the-fact edits; property tests
- [ ] `@rt/schedule`: interval, calendar, quota, window/grace/bounds, status, matching; TZ/DST/offline tests
- [ ] `@rt/templates`: Zod schema, generated JSON Schema, validator, `instantiate`, starter templates
- [ ] `@rt/insights`: bucketing with midnight/DST splits, streaks, adherence, quotas, heatmaps, histogram, records
- [ ] `@rt/notify`: shared-budget planner with priorities
- [ ] CLI: simulate a day and print the notification plan

## Phase 2 — Multi-timer app (offline)
- [ ] Spikes: AlarmKit, Live Activities, Android exact alarm + chronometer + full-screen intent, actions with app killed
- [ ] Expo scaffold, design tokens, SQLite + Drizzle
- [ ] `modules/timer-native`
- [ ] Now screen: running stack, quick start
- [ ] Quick countdown, stopwatch, presets, sequence builder, groups
- [ ] Sounds, haptics, notifications and actions
- [ ] Timer history; CSV/JSON export; delete all data
- [ ] Maestro reliability suite on real devices; internal test builds

## Phase 3 — Routines
- [ ] Routine builder (steps, fields, schedule, goals)
- [ ] Routine runner (guided/checklist), timed steps as real timers
- [ ] Today section, reminders, quota goals, preset → routine linking
- [ ] Templates gallery

## Phase 4 — Visualization
- [ ] Insights dashboard ("This week")
- [ ] Chart components + accessible summaries/tables
- [ ] Drill-downs, ranges, period comparison, personal records
- [ ] Shareable summary image

## Phase 5 — Monetization
- [ ] RevenueCat, paywall, restore, Terms/Privacy links
- [ ] Entitlement adapter + limits; downgrade behaviour

## Phase 6 — Polish & launch
- [ ] Widgets, onboarding, accessibility pass, performance sign-off
- [ ] Store assets, privacy labels, Data safety, exact-alarm and full-screen-intent declarations ⚠️

## Phase 7 — Accounts & premium cloud sync (post-launch)
- [ ] Supabase auth, schema + RLS, sync (per-field LWW, append-only sessions)
- [ ] Account deletion (in-app + web), privacy policy update ⚠️

## Phase log
- **2026-10-10 — Phase 0 rev 1** (care tracker) drafted.
- **2026-10-10 — Phase 0 rev 2.** Product changed to a routine tracker + multi-timer with visualization for both:
  - offline-only MVP, sync later as premium
  - medication dropped
  - timers moved first in delivery because they carry the main platform risk

  The docs live in `routine-timer/` on branch `ccr-b1304701-y6yy5e` until the new repo exists.
