# Routine Timer — Phase 0 Architecture & Plan (rev 2)

Status: **Proposed — awaiting approval** · Date: 2026-10-10 · Revision 2 (product rethink)

**Revision note.** Revision 1 was a care tracker for pets, plants, medication and so on. Revision 2 changes the product to a **routine tracker plus a
multi-timer, with data visualization for both**. Your direction for this revision:
- support all four timer kinds: countdown, stopwatch, interval/sequence, and timed routine steps
- link timers and routines
- ship generic templates and drop medication
- make the MVP offline-only, with premium cloud sync later

Revision 1 is in git history (commit `88e6501`).

Companion documents:
- [`spec-timers-routines-templates.md`](./spec-timers-routines-templates.md): timer plans, the timer engine, routines, schedule
  rules, the template format, and insights.
- [`decisions.md`](./decisions.md): lightweight ADRs.
- [`roadmap.md`](./roadmap.md): phase checklist.

No application code is written until this plan is approved.

---

## 1. Product in one paragraph

A mobile app with two jobs that share one record of what you did:
1. **Multi-timer.** Run any number of countdowns, stopwatches and interval sequences at the same time, reliably, even when the
   app is closed. This covers cooking, workouts, Pomodoro, laundry and practice sessions.
2. **Routine tracker.** Scheduled or on-demand routines made of steps. Steps can be timed, and routines can have time goals
   ("practice 3 h a week").

Every timer run and every routine run becomes a **session**. Visualizations are built on sessions:
- time spent per activity
- streaks and adherence
- calendar and time-of-day heatmaps
- trends in durations and in logged values

Templates (morning routine, Pomodoro, Tabata, "pasta night" cooking set, practice tracker, chores) get people started.

### What changed from revision 1

| Kept | Changed | Dropped from MVP |
|---|---|---|
| Pure-engine monorepo, Expo + strict TS | "Modules → trackables" becomes **templates → routines / timer presets** (templates are copied, not linked; see §3.2) | Medication module and the health-data obligations that came with it |
| Scheduling engine (interval, calendar, windowed, course) | New **timer engine** (event-sourced, see §5) | Weather, location, adaptive rules, meter/counter rules |
| Offline-first SQLite, typed log fields | New **quota rule** for time and count goals | Accounts, cloud sync, shared spaces (sync returns as premium in Phase 7) |
| Insights engine (now centred on sessions and durations) | Native modules for alarms and running-timer displays (§6) | Supabase in MVP (still the plan for sync later) |
| Freemium + RevenueCat | Premium value moves to **visualization and unlimited saved items** (§9) | Push notifications |

---

## 2. Assumptions

Please correct any of these that are wrong.

1. iOS + Android phones. Watch apps (Apple Watch, Wear OS) are a strong *later* feature, and the timer engine stays portable for them.
2. English first, with all strings externalized.
3. **No account, no server in MVP.** All data stays on the device. That allows a near-"Data Not Collected" privacy label
   (purchases and opt-out crash reports are the only disclosures).
4. Data per user is small: thousands of sessions per year, so in-memory aggregation is fine.
5. Templates are authored by us. Users can save their own routines and presets as personal templates, but there's no community sharing.
6. The code name `routine-timer` and package scope `@rt/*` are placeholders until a product name is chosen.

---

## 3. Domain model

```
Template ──instantiate (copy)──► Routine | TimerPreset
Routine 1─* Step   (check | countdown | stopwatch | sequence | input)
Routine 0..1 Schedule (interval | calendar | quota, + window/bounds)
TimerPreset: countdown | stopwatch | sequence | group
Session = one run of a Routine or a TimerPreset (or an ad-hoc quick timer)
Session 1─* SessionEvent (start, pause, resume, advance, adjust, lap, finish, cancel …)
Tag *─* Routine / TimerPreset / Session   (for grouping in charts: "Work", "Fitness")
```

### 3.1 Concepts

- **Timer preset.** A saved timer configuration. Kinds:
  - `countdown` ("Tea 4:00"), with optional overtime count-up
  - `stopwatch` ("Guitar practice"), with laps and an optional safety cap
  - `sequence` ("Pomodoro 25/5 ×4 + 15", "Tabata 20/10 ×8"), with nested repeat blocks and manual-advance phases
  - `group`, several countdowns started together **or staggered so they finish together** ("Pasta night": pasta 10 min,
    sauce 15 min, garlic bread 8 min, all ready at once)
- **Routine.** A named set of ordered **steps** with an optional schedule, reminder settings, an optional time/count goal,
  and optional log fields recorded at the end (e.g. mood rating, notes). Step types:
  - `check`: tick it off
  - `countdown` / `stopwatch` / `sequence`: a timed step that runs a real timer
  - `input`: record fields mid-routine

  A single-step routine is valid: "Read 20 min daily" is a routine with one countdown step and a daily schedule.
- **Session.** The single record type for "something was done". It has a kind (`routine` or `timer`), a reference to the routine or
  preset (or none for a quick timer), `occurrence_at` when it satisfies a scheduled routine occurrence, a status (`active`,
  `completed`, `abandoned`, `skipped`), start and end times, active duration, a **snapshot of the plan** (so history stays
  correct after a preset is edited), the event log, per-step results, field values, notes and tags.
- **Occurrence.** A scheduled routine occurrence. As in revision 1, occurrences are **derived** by the scheduling engine and never stored.

### 3.2 Key modelling decisions

- **Templates are copied, not referenced.** Instantiating a template creates an ordinary editable routine or preset.
  Revision 1's module-versioning and migration machinery (pinning trackables to module versions) is no longer needed.
  Templates still have a versioned, validated JSON format, because they ship in the app and (later) from a remote catalog.
- **The timer state is an event log, not a counter.** Elapsed and remaining time are *computed* from timestamps (§5). This one
  decision makes timers survive app kills, reboots and backgrounding, and makes them unit-testable.
- **Timed routine steps are real timers.** Starting a timed step creates a child timer session that appears in the multi-timer
  stack. You can leave the routine screen, start a second timer, and come back. Finishing the last step completes the routine session.
- **Standalone timers can count toward routines.** Sessions from a preset can be linked to a routine, so a Pomodoro run can count
  toward a "Deep work 10 h/week" goal. Linking is set on the routine ("count sessions of these presets/tags").
- **Sync-ready from day one, without sync.** UUIDv7 IDs, HLC (hybrid logical clock) columns, field clocks and tombstones are in
  the local schema now, so Phase 7 sync is additive. Sessions and events are append-only, which suits the sync design kept in §11.

---

## 4. Architecture

```
┌──────────────────────────────────────────────────────────────┐
│ apps/mobile (Expo, expo-router)                               │
│  Screens · design system · dynamic field forms · charts       │
│  Application services: startTimer(), completeStep(), …        │
│  Adapters: SQLite repos · notifications · native timer module │
│            · purchases · audio/haptics · files/export         │
├──────────────────────────────────────────────────────────────┤
│ modules/timer-native (Expo local native module)               │
│  iOS: AlarmKit + Live Activities · Android: exact alarms +    │
│  chronometer notification + full-screen ringing               │
└──────────────▲───────────────────────────────▲────────────────┘
               │ pure function calls           │
┌──────────────┴───────────────────────────────┴────────────────┐
│ packages (pure TS — no React, no I/O, time injected)          │
│  @rt/core        ids, HLC, time wrapper (Temporal), durations │
│  @rt/timer       plans, event reducer/projection, boundaries  │
│  @rt/schedule    routine rules → occurrences, status, quota   │
│  @rt/templates   schema (Zod) + JSON Schema, validator,       │
│                  instantiate(), built-in template library     │
│  @rt/insights    session aggregation, streaks, heatmaps,      │
│                  trends, chart-ready series, CSV/report HTML  │
│  @rt/notify      notification planner (timers + routines)     │
└───────────────────────────────────────────────────────────────┘
```

Rules:
- Packages never import React or React Native, and never read the clock. `now` and the time zone are always passed in.
- `@rt/timer` has **no dependency** on routines or templates. It's the piece we'd reuse first in a watch app or widget.
- Screens call application services. Services combine DB, engines and side effects, and are tested without rendering.

### 4.1 Stack

| Area | Choice | Notes |
|---|---|---|
| App | Expo (latest stable SDK at scaffold), dev builds, expo-router, TS strict | Unchanged |
| Native | **Expo Modules API** local module `timer-native` (Swift + Kotlin) | New. Timers need OS features no JS library covers well (§6) |
| Date/time | Temporal via `temporal-polyfill`, behind `@rt/core/time`; Luxon fallback | Hermes spike in Phase 1 |
| Schemas | Zod as source of truth; generated JSON Schema for templates | Unchanged reasoning (no runtime codegen) |
| Local DB | `expo-sqlite` + Drizzle (migrations, typed queries, live queries) | Unchanged |
| Notifications | `expo-notifications` for scheduled local notifications and actions | Plus native alarm paths (§6) |
| Audio/haptics | `expo-audio`, `expo-haptics` | In-app ringing and phase cues |
| Charts | `react-native-svg` + `d3-scale`/`d3-shape`/`d3-array`, hand-built components | More chart types now (§8). `victory-native` is the alternative if we want rich gestures and animation |
| Export | CSV/JSON from pure TS; `expo-sharing`; optional PDF via `expo-print` | |
| Purchases | RevenueCat (`react-native-purchases`) | Unchanged |
| Crash reporting | Sentry, PII scrubbed, opt-out | |
| Tests | Vitest + fast-check (packages), jest-expo + RNTL (app), Maestro for device flows (timer reliability) | Maestro is new: background/kill scenarios need device-level tests |
| Later: sync | Supabase (Auth, Postgres + RLS) with the per-field LWW design (§11) | Deferred to Phase 7 |

---

## 5. Timer engine (`@rt/timer`)

The engine is the heart of the multi-timer and has to be correct when the app isn't running.

### 5.1 Model

- A **plan** is immutable once started (a snapshot taken from the preset). Sequences are expanded into a flat list of **phases**
  (with a limit of 500 phases). Each phase has a label, an optional duration (no duration means manual advance), a tone
  (`prep`/`work`/`rest`/`other`) and a cue.
- A running timer is a list of **events**, each with a timestamp: `start`, `pause`, `resume`, `advance` (manual phase done or
  skip), `back`, `adjust(±ms)` ("+1 min"), `lap`, `ack` (dismiss ringing), `finish`, `cancel`.
- `project(plan, events, now)` → `{ status, phaseIndex, phaseElapsed, phaseRemaining, totalElapsedActive, totalRemaining,
  overtime, laps, ringingSince }`. It's a pure reducer over events, followed by arithmetic for `now`. Nothing ticks.
- `boundaries(plan, events, now, horizon)` → the future instants where something happens (phase end, timer end, a group
  member's staggered start), **assuming no further user action**. These drive notifications, alarms, Live Activities and
  in-app cues. A paused timer has no boundaries.
- Status machine: `running ⇄ paused`, `running → ringing` (countdown reached zero; overtime counts up if enabled),
  `ringing → finished` (on `ack`), and any state `→ cancelled`. Stopwatches finish on `finish`.

### 5.2 Groups ("finish together")

For `align: finishTogether`, member *i* starts at `groupStart + (maxDuration − duration_i)`. Staggered starts are boundaries,
so the user gets "Start the garlic bread now" with a **Started** action. If they start it late, the member's actual start
event is what counts. The engine shows the new projected finish and never pretends the plan still holds.

### 5.3 Clock correctness

- Events are stored as wall-clock instants, so they survive reboots.
- While the app is alive, the adapter compares wall-clock and monotonic deltas. A jump (manual clock change, NTP correction) is
  recorded as a `clockCorrection` event so projections stay right. The scenario is covered by tests.
- A stopwatch has a default safety cap of 12 h. Users can **edit start/end after the fact** ("I forgot to stop it"). The edit is
  recorded as events, and the original values are kept.

### 5.4 Tests

- Property tests: projected elapsed time is monotonic in `now` while running; pause/resume sequences conserve active time;
  `boundaries` are strictly increasing; projecting at a boundary instant lands exactly on the phase change.
- Scenarios: a 40/20 ×8 sequence with the app killed after round 2; pause across midnight and across DST; "+1 min" during
  overtime; group finish-together with a late member start; a clock jump of ±1 h mid-timer.

---

## 6. Making timers reliable on real phones ⚠️ biggest technical risk

A timer app is only as good as its alarm firing when the screen is off and the app is closed. JS doesn't run in the background,
so every future event must be **handed to the OS ahead of time** from `boundaries()`.

| Need | iOS | Android |
|---|---|---|
| Timer end must alert even if the app is killed | **AlarmKit** (iOS 26+): system alarms and timers that break through Silent/Focus and show on the Lock Screen/Dynamic Island. Older iOS: scheduled local notification with a ≤30 s sound | `AlarmManager` exact alarm (`setExactAndAllowWhileIdle` / `setAlarmClock`) → notification with sound; **full-screen intent** for ringing |
| Show running timers outside the app | **Live Activities** (one per running timer, rendered natively with a system countdown, no updates needed) | **Ongoing notification with a chronometer** (counts down natively), one per timer or grouped |
| Phase cues for sequences while backgrounded | Scheduled local notifications per boundary (budget-limited, §7) | Exact alarms per boundary |
| In-app | `expo-audio` looped ring until dismissed, haptics, screen-reader announcements | Same |

- All of this lives in **one small Expo local module** (`modules/timer-native`) with a narrow API:
  `scheduleAlarm`, `cancelAlarm`, `startActivity`, `updateActivity`, `endActivity`. The JS side only passes instants computed
  by the engine. If a maintained community module covers part of this when we get there, we use it instead.
- We avoid an Android **foreground service**. Alarms plus a chronometer notification give the same user experience without the
  Android 14 foreground-service-type review and the battery cost.
- Phase 2 **starts with spikes** on this (AlarmKit, Live Activities, Android exact alarm + full-screen intent) on real devices,
  before any UI polish.

**Store/permission flags**
- Android: `USE_EXACT_ALARM` is allowed by Google Play for apps whose core function is an alarm or timer, which this is, but
  we must declare it and it will be reviewed. Fallback: `SCHEDULE_EXACT_ALARM` with a user prompt.
  `USE_FULL_SCREEN_INTENT` is restricted on Android 14+ to calling/alarm apps and needs a Play declaration.
  `POST_NOTIFICATIONS` runtime permission on Android 13+.
- iOS: AlarmKit requires user authorization (a usage-description string). We do **not** request Critical Alerts (that entitlement
  is for health and safety apps). Background audio, for spoken workout cues while locked, is a later feature and needs to be
  justified to App Review.

---

## 7. Notifications planner (`@rt/notify`)

- iOS allows **64 pending local notifications per app**. One budget is shared by timer boundaries and routine reminders.
- Priority order:
  1. the final end of each running timer (AlarmKit, where available, doesn't count against the budget)
  2. the next phase cues of running sequences
  3. group staggered starts
  4. routine reminders, soonest first (7-day rolling window)
- Routine reminders that fall in the same minute are bundled into one notification. If a long sequence would need more cues than the budget allows,
  the planner schedules the earliest ones plus a "Timer continues — open to keep cues" notice. **Final alarms are never dropped.**
- Re-plan on: any timer event, routine edit, app foreground/background, and notification receipt. The planner diffs against what's
  already scheduled.
- Notification actions:
  - timers: **Stop**, **+1 min**, **Snooze** (countdown); **Next** (manual phase)
  - routines: **Start**, **Done** (1 tap), **Skip**

---

## 8. Insights and visualization (`@rt/insights`)

Everything is computed from sessions. The engine returns chart-ready series plus a plain-language summary for each chart
(for accessibility and reports).

**Aggregations**
- Time spent per day, week or month, grouped by preset, routine or tag.
- Sessions crossing midnight or a DST change are **split across buckets in the local time zone**. This is a common source
  of wrong charts, so it's tested explicitly.

**Routine metrics**
- Adherence: % of occurrences done on time.
- Current and longest streak.
- Quota progress per period, and how often the goal was met.
- Time-of-completion distribution.
- Routine duration trend.
- Per-step time breakdown: which step takes the longest.

**Timer metrics**
- Total and average time per activity.
- Duration histogram.
- Countdown **overrun rate** (how often and how far past zero).
- Completed focus phases (Pomodoros) per day.
- Laps and splits.

**Logged fields**
- Trend with moving average for numeric fields (e.g. mood, reps, weight lifted).
- Distribution for enum fields.
- % true for boolean fields.

**Personal records:** longest session, best week, longest streak.

**Chart set (MVP):**
- stacked bar (time per bucket)
- line with moving average
- calendar heatmap (GitHub-style year/month)
- weekday × hour heatmap
- histogram
- progress ring (quota)
- sparkline

**Screens:**
- an **Insights dashboard** ("This week": total tracked time, routines done, streaks, top activities)
- drill-downs per routine, preset and tag
- a date-range picker and a comparison with the previous period

**Export:** CSV and JSON of all sessions and definitions, **always free**. A shareable summary image or PDF is premium.

Correlations ("you practice longer on days you do your morning routine") are a later feature, presented as observations, not conclusions.

---

## 9. Monetization (no sync at launch)

With no server costs at launch, premium has to earn its price through **depth**: visualization, unlimited saved items, and power
features. **Reliability is never paywalled.**

| Feature | Free | Premium |
|---|---|---|
| Running timers at once | Unlimited | Unlimited |
| Countdown, stopwatch, sequence, group | ✓ | ✓ |
| Alarms, Live Activities, notification actions | ✓ | ✓ |
| Saved timer presets | 6 | Unlimited |
| Routines | 3 | Unlimited |
| Schedules, reminders, timed steps, quota goals | ✓ | ✓ |
| History list (all time, never deleted) | ✓ | ✓ |
| Charts | Last 7 days, basic bar + current streak | All ranges, every chart type, drill-downs, comparisons, personal records |
| Templates | Starter set | Starter set + extended library |
| Custom templates (save your own) | — | ✓ |
| CSV/JSON export, delete all data | ✓ always | ✓ |
| Shareable summary image/PDF | — | ✓ |
| Home-screen widgets | Basic "next up" | Configurable widgets |
| **Later:** cloud sync & multi-device | — | ✓ |

Products: monthly, annual, lifetime (non-consumable). **Open question Q2:** without sync, a subscription may be a harder sell than
a one-time unlock, and Apple expects subscriptions to deliver ongoing value. Options:
- (a) subscription + lifetime from day one
- (b) **one-time "Pro" unlock at launch, then a subscription tier added with sync, while grandfathering lifetime buyers** (recommended)

All limits are remote-configurable later. For now they're bundled constants behind an entitlement adapter.

---

## 10. Privacy, accessibility, performance

**Privacy**
- No account and no server in MVP. Data never leaves the device except through user-initiated export, the RevenueCat purchase
  identifiers, and opt-out crash reports.
- No product analytics SDK in MVP.
- **Delete all data** in settings.
- Templates contain no health claims. Workout templates (HIIT, Tabata) carry a short generic "exercise at your own risk"
  line, and there's no HealthKit or Health Connect integration in MVP.
- Subscription UI follows Apple 3.1.2: price, period and renewal terms, Restore Purchases, and Terms and Privacy links.

**Accessibility**
- Timer digits scale with Dynamic Type, using a layout that reflows rather than truncating.
- Screen-reader announcements happen at phase changes and on finish, never every second.
- Running timers expose actions (pause, +1 min, stop) as accessibility actions.
- State is never shown by colour alone: work and rest phases get a label and an icon.
- Charts have text summaries and data tables.
- Reduce Motion disables ring animations.
- Haptic and flash alternatives are offered for audio cues.

**Performance**
- One shared ticker drives all visible timers. Only on-screen timers re-render. Hundredths are shown only on a focused stopwatch.
- Cold start renders the Now screen from SQLite in < 1.5 s on a mid-range Android device. Charts and builders are lazy routes.
- **Starting a saved timer takes 1 tap** from the Now screen, a widget or a Live Activity. Completing a routine reminder takes 1 tap from the
  notification.

**Widget-friendliness**
- On every state change, the app writes a snapshot file to shared storage (iOS App Group, Android SharedPreferences) containing
  running timers (as end instants) and the next routines due.
- Widgets and Live Activities render from instants with native countdown views, with no JS runtime involved.

---

## 11. Later: premium cloud sync (design kept from revision 1)

This is not built in MVP, but the local schema is shaped for it now.
- **Backend:** Supabase (Auth with Apple/Google/email, Postgres + RLS, Storage if photos are ever added).
- **Mechanism:** an outbox of field-level patches, an idempotent `apply_changes` RPC, and pulls by `server_seq` cursor.
  Merges are per-field last-writer-wins on hybrid logical clocks, using one merge function implemented in both TS and SQL and
  checked against shared test vectors.
- **Sessions and events are append-only**, so they never conflict.
- **Timers running on two devices** stay independent sessions. A future "handoff" (start on phone, see it on tablet or watch) falls out
  naturally, because a timer is just its event log.
- Shared/household features remain out of scope until after sync ships.
- Legal: once data leaves the device we need a privacy policy update, GDPR processing terms, and in-app **account deletion**
  (Apple 5.1.1(v)) plus a web deletion URL (Google Play).

---

## 12. Folder structure

```
routine-timer/
├── apps/mobile/
│   ├── app/                    routes: (tabs)/now, routines, insights, settings; timer/[id]; routine/[id]/run; …
│   └── src/
│       ├── services/           startTimer, adjustTimer, runRoutine, completeStep, logSession, …
│       ├── features/           now/ timers/ routines/ runner/ builder/ insights/ templates/ paywall/ settings/
│       ├── ui/                 tokens, components, timer displays, chart components, field renderers
│       ├── data/               drizzle schema, migrations, repositories
│       ├── platform/           notifications, timer-native bridge, audio, haptics, purchases, export
│       └── test/
├── modules/timer-native/       Expo local module (ios/ Swift: AlarmKit, ActivityKit widget; android/ Kotlin)
├── packages/
│   ├── core/  timer/  schedule/  templates/  insights/  notify/
├── e2e/                        Maestro flows (kill app mid-timer, reboot, DST, notification actions)
├── docs/
├── .github/workflows/          ci.yml (typecheck, lint, unit, template validation), eas-build.yml
└── package.json  pnpm-workspace.yaml  tsconfig.base.json  vitest.workspace.ts
```

---

## 13. Delivery plan (revised)

Timers come first. They're useful on their own every day, and they carry the biggest platform risk, so we find out early.

**Phase 0 — Plan.** This document. Exit: approval and answers to the open questions.

**Phase 1 — Core engines (pure TS, no UI)**
- Spike: Temporal on Hermes.
- Monorepo scaffold and CI.
- `@rt/core`.
- `@rt/timer`: plans, sequences with nested repeats, groups, projection, boundaries, clock correction.
- `@rt/schedule`: interval, calendar, quota, window, bounds, status, matching.
- `@rt/templates`: schema + validator + the starter templates.
- `@rt/insights`: bucketing with midnight/DST splits, streaks, adherence, heatmap and histogram series.
- `@rt/notify`: the planner.
- Exit: ≥95% coverage on timer/schedule/notify; all templates validate; a CLI simulates a day of timers and routines and
  prints the notification plan.

**Phase 2 — Multi-timer app (offline)**
- **Spikes first:** AlarmKit, Live Activities, Android exact alarm + chronometer + full-screen intent, notification actions with the app killed.
- Expo scaffold, SQLite/Drizzle.
- **Now screen** with a running-timer stack and quick start.
- Quick countdown and stopwatch, presets, sequence builder, groups.
- Sounds and haptics, notifications, `timer-native` module.
- Timer history list, CSV/JSON export.
- Exit: Maestro reliability suite passes on real iOS and Android devices; internal TestFlight / Play testing build.

**Phase 3 — Routines**
- Routine builder (steps, fields, schedule, goals).
- Routine runner with timed steps as real timers.
- Today section on Now.
- Reminders, quota goals, linking presets to routines.
- Templates gallery.
- Exit: daily dogfood for 2 weeks.

**Phase 4 — Visualization**
- Insights dashboard, all chart types, drill-downs, date ranges and comparisons.
- Accessible summaries.
- Personal records.
- Shareable summary image.

**Phase 5 — Monetization**
- RevenueCat, paywall, limits behind the entitlement adapter.
- Lifetime/Pro unlock (per Q2).
- Restore.
- Downgrade behaviour: never delete anything. Over-limit items stay runnable but not editable (Q3).

**Phase 6 — Polish & launch**
- Home-screen widgets.
- Onboarding.
- Accessibility pass.
- Performance sign-off.
- Store assets, privacy labels, Data safety form, Play exact-alarm and full-screen-intent declarations.

**Phase 7 — Accounts & premium cloud sync** (post-launch)
- Supabase, sync per §11, account deletion.
- Then, candidates for later: watch apps, sharing, voice cues, correlations, HealthKit / Health Connect, smart scheduling.

---

## 14. Open questions

| # | Question | Recommendation |
|---|---|---|
| Q1 | **Repo and name.** I still can't create repos from this session (GitHub 403). Please create an empty private repo and grant access. What name? | A neutral repo name such as `routine-timer` until branding is decided, with `main` as the default branch |
| Q2 | Pricing model without sync: subscription + lifetime now, or a one-time Pro unlock now and a subscription when sync arrives? | **One-time Pro at launch**, add the subscription with sync, grandfather lifetime buyers |
| Q3 | Free limits: 6 presets / 3 routines / 7-day charts? And should over-limit items still be *runnable* after a downgrade? | **Yes to the limits; yes, still runnable but not editable** (never break someone's morning alarm) |
| Q4 | Minimum iOS version. AlarmKit is iOS 26+. Do we support older iOS with notification-only alarms? | **Support iOS 17+**, with AlarmKit on 26+ and the notification fallback (documented as less loud) |
| Q5 | Watch apps (Apple Watch / Wear OS): confirm they're post-MVP? | **Post-MVP.** The engine stays portable |
| Q6 | Spoken voice cues for workouts ("Rest — 10 seconds") while the phone is locked? Needs background audio | **Later**, with tones/haptics only in MVP |
| Q7 | Temporal polyfill vs Luxon | **Temporal**, subject to the Phase 1 spike |
| Q8 | Keep the old care use cases (plant watering, pet feeding) as simple routine templates? | **Yes, as generic "care" routine templates**, with no medication |
| Q9 | Sentry crash reporting (opt-out), or nothing at all for a cleaner privacy label? | **Sentry, opt-out**. Timer reliability bugs are hard to find without it |
| Q10 | Design direction / brand? | I'll propose minimal design tokens in Phase 2 unless you have a direction |
