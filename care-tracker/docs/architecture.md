# Care Tracker — Phase 0 Architecture & Plan

Status: **Proposed — awaiting approval** · Date: 2026-10-10

This document is the Phase 0 deliverable. It covers the stack, architecture, data model, sync strategy,
notifications, monetization boundaries, privacy and policy flags, folder structure, a refined phase plan,
and open questions. The module definition format, schedule rule format and expression language are
specified in detail in [`spec-modules-and-scheduling.md`](./spec-modules-and-scheduling.md).
Decisions are recorded as lightweight ADRs in [`decisions.md`](./decisions.md); progress is tracked in
[`roadmap.md`](./roadmap.md).

No application code is written until this plan is approved.

---

## 1. Summary of the proposal

| Area | Recommendation | Confidence |
|---|---|---|
| App | React Native + Expo (dev builds, not Expo Go), TypeScript strict, expo-router | High (agree with your preference) |
| Repo shape | pnpm monorepo: pure engine packages + one Expo app + `supabase/` | High |
| Date/time | `Temporal` API via a small polyfill, isolated behind an internal `time` module; Luxon as fallback | Medium — needs a Hermes spike in Phase 1 |
| Module schema | **Zod** as the source of truth, JSON Schema **generated** from it for authoring and remote validation | High (a small challenge to "JSON Schema first", see ADR-004) |
| Rules | Structured JSON rules plus a tiny typed expression AST. No parser, no `eval`, bounded evaluation | High |
| Local DB | `expo-sqlite` + Drizzle ORM (migrations, typed queries, live queries) | High |
| Backend | Supabase: Postgres + RLS, Auth, Storage, Edge Functions, pg_cron | High (agree) |
| Sync | Custom, small: **per-field last-writer-wins with hybrid logical clocks** for mutable entities, **append-only** log entries, server sequence cursor for pulls | Medium-high. PowerSync is the main alternative (see §6.6) |
| Subscriptions | RevenueCat (`react-native-purchases`): one `premium` entitlement, monthly + annual + lifetime (non-consumable) | High |
| Notifications | `expo-notifications` local reminders on a rolling window. Expo Push for shared-space events | High |
| Charts | `react-native-svg` (already part of Expo) + `d3-scale`/`d3-shape`, with a few hand-built chart components | Medium. `victory-native` is the alternative |
| PDF / CSV | Pure TS report builder produces HTML/SVG/CSV. `expo-print` renders the PDF and `expo-sharing` shares it | High |
| Tests | Vitest + `fast-check` (property tests) for packages. `jest-expo` + React Native Testing Library for the app | High |
| Custom module builder | **Free** (fields, tasks, fixed and calendar schedules). Adaptive rules and insight configuration inside the builder are premium | Recommendation |

Two **engine extensions beyond the brief** came out of writing the starter modules. Per your instruction,
I'm flagging them as design issues now rather than discovering them later (details in §3.3):

1. **Multi-instance task types with per-instance config.** A dog has several medications, each with
   its own name, dose and schedule.
2. **Attribute effects + meter rules + "whichever comes first".** Medication refills (supply counts
   down) and, later, vehicles (oil change every 5,000 miles *or* 6 months) need these.

---

## 2. Assumptions

Please correct any of these that are wrong.

1. Launch targets are iOS and Android phones. Tablets work but are not optimized. No web app in MVP.
2. English-only at launch, but every user-facing string is externalized from day one so i18n is cheap later.
3. Initial markets are US/EU/UK/CA/AU, so GDPR and US state health-privacy laws apply (see §10).
4. Minimum OS versions follow whatever the current Expo SDK supports at scaffold time (roughly iOS 15.1+ / Android 7+).
5. The free tier needs **no account**. Everything is local until the user signs up, which is a strong privacy story.
6. Data volume per user is small: tens of trackables and tens of thousands of log entries over years.
   Insights can run in memory per trackable, so we don't need an analytics store.
7. You are a solo developer or small team, so "minimal ops" matters more than theoretical scalability.
8. Every built-in module and care profile is authored by us. User-generated modules stay private to the user or space (there's no community sharing).
9. Product name and branding are TBD. The code name is `care-tracker` and the package scope is `@care/*`.

---

## 3. Domain model

Terms are used identically in code, DB and UI copy.

```
Space (household) 1─* SpaceMember *─1 User
Space 1─* Trackable *─1 ModuleDefinition@version
Trackable 1─* Task  (instance of a module TaskType, with per-instance config + ScheduleRule)
Task 1─* LogEntry (done | skipped; typed values per the TaskType log schema; notes; photos)
LogEntry 1─* Attachment
```

### 3.1 Key modelling decisions

- **Every trackable belongs to a space.** A free, signed-out user gets one implicit *personal space* that lives on the
  device. Sharing just means adding members to a space, so there's no data migration when a user upgrades. (ADR-006)
- **Occurrences are derived, not stored.** The schedule engine computes occurrences from the rule plus the log history.
  We only persist what the user did (done/skipped log entries). "Missed" is computed. This keeps sync tiny and
  makes rule edits retroactively consistent. (ADR-008)
- **Log entries reference the occurrence they satisfy** (`occurrence_at`), or `null` for ad-hoc entries. This lets
  two members logging the same 8am dose be *detected* as a double completion (see §6.4).
- **Log values are keyed by stable field IDs**, never labels, and stored as JSON with the module version they were recorded under.
- **Module definitions are append-only across versions.** Fields and tasks are deprecated, never deleted or retyped.
  Old log entries therefore always remain readable, and we never rewrite history. (ADR-009)
- **Who did it:** `performed_by_member_id` (space member), plus an optional free-text `performed_by_name` for
  people without an account (a dog walker, a grandparent).

### 3.2 Task types vs tasks

A module declares **task types** (templates). A trackable has **tasks** (instances). A task type may be:

- `single` — at most one instance per trackable (e.g. "Water" for a plant).
- `multi` — many instances, each with its own `config` values (e.g. "Medication dose" on a dog: *Heartgard,
  1 chew, monthly* and *Apoquel, 16 mg, daily*).

Each task stores its own schedule rule (seeded from the type's default or the care profile) and reminder settings.
Users can always edit the schedule of an individual task.

### 3.3 Engine gaps found while writing the starter modules (flagged)

| Need | Module(s) | Why the brief's model can't express it | Proposed generic fix |
|---|---|---|---|
| Several medications on one pet, each with its own name/dose/schedule | Dog/Cat, Medication | Task types were implicitly one-per-trackable | `multi` task types with `configFields` |
| Refill reminders when supply runs low | Medication (and aquarium test kits, HVAC filter stock) | No link between logging a dose and a remaining-supply count | **Counters** (numeric fields on the trackable or on a task's config). **Effects:** logging can `add`/`set` a counter. **Secondary actions** on a task type (e.g. "Refill" on a dose task, with its own log fields and effects). **Meter conditions:** a rule or a task alert that fires when a counter crosses a threshold |
| "Every 5,000 miles or 6 months, whichever first" | Vehicle (future), HVAC (runtime hours, future sensor) | Rules are single-kind | `anyOf` composite rule; meter rule supports "usage since last completion" |
| Sourdough hydration % | Sourdough | Derived from other fields | Optional `computed` log fields using the same expression AST. Could be insights-only if we want to defer |
| "Season" for plants | Houseplant | Season depends on hemisphere | `ctx.hemisphere` from user settings or coarse location; default by locale |

All of these are **generic** additions to the module format and engines. No category-specific code is involved. I recommend
including all of them in Phase 1 except `computed` fields (Phase 6, optional).

---

## 4. Architecture

### 4.1 Layers

```
┌──────────────────────────────────────────────────────────────┐
│ apps/mobile (Expo)                                            │
│  UI (expo-router screens, design system, dynamic forms)       │
│  Application services: completeTask(), addTrackable(), ...    │
│  Adapters: SQLite repos · sync client · notifications ·       │
│            purchases · weather · files                        │
└──────────────▲───────────────────────────────▲────────────────┘
               │ pure function calls           │
┌──────────────┴───────────────────────────────┴────────────────┐
│ packages (pure TypeScript — no React, no I/O, no Date.now())  │
│  @care/core        ids, HLC, units, time wrapper, expr AST    │
│  @care/modules     schema (Zod), validator, interpreter,      │
│                    migrations, built-in module library        │
│  @care/scheduling  rules → occurrences, status, notif plan    │
│  @care/insights    trends, thresholds, adherence, reports     │
│  @care/sync        HLC merge, per-field LWW, change batching  │
└───────────────────────────────────────────────────────────────┘
               ▲ same packages, bundled for Deno where needed
┌──────────────┴────────────────────────────────────────────────┐
│ supabase/  Postgres schema + RLS · Edge Functions · pg_cron   │
└───────────────────────────────────────────────────────────────┘
```

Rules:
- Packages never import from the app or from React Native. `now`, time zone, weather snapshots and
  entitlement flags are **passed in**. Every engine call is deterministic and replayable.
- Package dependency direction: `core ← modules ← insights`, `core ← scheduling ← insights`, `core ← sync`.
  `scheduling` deliberately does **not** depend on `modules`, so it can be reused for anything with a rule
  (e.g. a widget or a web app).
- The app's *application services* are the only place that combines DB, engines and side effects. Screens call services.
  This keeps the logic in screens testable without rendering.

### 4.2 Why these choices (and alternatives considered)

**React Native + Expo (agree).** Flutter would lose the shared TypeScript core between the app, edge functions and a future
web app or widget. Fully native would roughly double the cost. Expo dev builds with config plugins cover everything we need
(SQLite, notifications, purchases, widgets later) without ejecting. EAS Build/Submit/Update handle CI builds and OTA JS fixes.

**Supabase (agree).** Relational data with per-space access control maps cleanly onto Postgres + RLS. Firebase/Firestore
would make the space/role permissions and SQL reporting awkward. Supabase also gives Auth (Apple, Google, email magic
link), Storage (photos) and Edge Functions (webhooks, push, account deletion, weather proxy) in one managed product.

**Temporal over Luxon/date-fns.** Scheduling needs a clear split between *wall-clock* times ("8:00 every day in my home
zone") and *exact instants* ("every 8 hours"). Temporal models this directly (`PlainTime`, `ZonedDateTime`, `Instant`,
explicit DST disambiguation). It is native in modern browsers and Deno, but **not in Hermes**, so we'd ship a polyfill
(`temporal-polyfill`, ~20 kB gz). Phase 1 starts with a 1-day spike to confirm correctness and performance on Hermes
(Android in particular). If it fails, we fall back to Luxon behind the same internal `time` wrapper.

**Zod as schema source of truth (small challenge).** You asked for JSON Schema validation. Ajv, the standard JSON Schema
validator, compiles validators with `new Function`, which is a poor fit for Hermes and for "no code evaluation". Instead:
module definitions are described in Zod (no codegen, works everywhere, gives TS types). We **generate** a JSON Schema file from
it (`z.toJSONSchema`) for editor autocomplete, remote-module authoring and external tooling. JSON Schema can't express
semantic checks anyway (references resolve, expressions type-check, IDs are unique, versions are backward compatible),
so a second validation pass in `@care/modules` covers those.

**Custom sync vs PowerSync.** See §6.6.

**Charts.** We need three chart kinds: a line with a threshold band, a bar/heat strip for adherence, and a sparkline.
`react-native-svg` + `d3-scale`/`d3-shape` adds two small pure dependencies, gives full accessibility control, and the
same SVG markup can be emitted into PDF reports. `victory-native` (Skia-based) is faster for very large series but brings in
Skia + Reanimated. Not needed at our data sizes.

**PDF.** `expo-print` renders HTML to PDF with the OS engine and needs no extra native dependency. `@care/insights` builds the
report HTML (tables + inline SVG charts) as a pure function, so a future web app or server can produce the same report.

---

## 5. Data model

The local SQLite schema and the Postgres schema mirror each other for synced tables. Every synced table has:

| Column | Purpose |
|---|---|
| `id uuid` | UUIDv7, generated on device (sortable, no server round-trip) |
| `space_id uuid` | Partition and RLS key |
| `created_at`, `updated_at` | Display only. **Not** used for conflict resolution |
| `hlc text` | Hybrid logical clock of the latest write to the row |
| `field_clocks jsonb` | `{ fieldName: hlc }` for per-field LWW (mutable tables only) |
| `deleted boolean` | Tombstone (an LWW field like any other) |
| `server_seq bigint` | Server only. Assigned from a global sequence on every write, used as the pull cursor |

### 5.1 Tables

**Synced**

| Table | Key columns | Mutability |
|---|---|---|
| `spaces` | name, owner_user_id, home_time_zone, hemisphere, units_override | LWW per field |
| `space_members` | space_id, user_id, role (`owner`/`admin`/`member`/`viewer`), display_name, notification_prefs json | LWW per field |
| `space_invites` | space_id, role, token_hash, expires_at, created_by | server-managed |
| `module_definitions` | module_id, version, source (`builtin`/`remote`/`custom`), definition json, content_hash. Only custom modules are synced, scoped to a space. Built-ins ship with the app; remote ones are cached | custom: new row per version |
| `trackables` | module_id, module_version, care_profile_id, name, photo_attachment_id, attributes json (+ per-key clocks), archived_at, sort_key | LWW per field (attributes per key) |
| `tasks` | trackable_id, task_type_id, config json, schedule json, reminder json, time_zone_mode, enabled, paused_until, assignee_member_id, next_due_at*, alert_at* | LWW per field |
| `log_entries` | task_id, trackable_id, action_id (`complete` or a secondary action like `refill`), status (`done`/`skipped`), occurrence_at, performed_at, performed_by_member_id, performed_by_name, values json, notes, module_version, effects_applied json | **Insert-mostly**. Edits and deletes are LWW per field |
| `attachments` | log_entry_id \| trackable_id, storage_path, mime, width, height, bytes, upload_state | LWW |

\* `next_due_at` / `alert_at` are *derived* values that the client computes with the schedule engine and syncs.
The server's missed-task cron then needs only SQL; it never re-implements scheduling (§8.3).

**Server only:** `profiles` (user settings that follow the account), `entitlements` (from RevenueCat webhooks),
`push_tokens`, `notification_outbox`, `remote_module_catalog`.

**Local only:** `outbox` (pending changes), `sync_state` (cursors per table), `scheduled_notifications`
(OS notification ID ↔ task/occurrence), `task_state` (derived cache: last_done_at, next occurrence, status, used by Today
and widgets, fully rebuildable), `kv` (settings, feature flags, remote config cache).

### 5.2 Querying logs

Insights load a trackable's log entries into memory and work on plain arrays. SQLite JSON1 (`json_extract`) covers the
occasional filtered query. If profiling ever shows a need, we can add a derived `log_numeric_values(log_id, field_id, value)`
table locally. It isn't needed for MVP.

---

## 6. Sync strategy

Goal: offline-first, simple enough for one person to reason about, correct for the conflicts this app actually has.

### 6.1 Conflict profile of this app

- Most writes are **new log entries**. These never conflict, because each has a fresh UUID.
- Edits to trackables, tasks and settings are rare and usually touch different fields.
- The dangerous case is **semantic**, not technical: two people both give the 8am dose. No merge strategy can
  undo a real double dose. The app must **prevent it when online and surface it when offline** (§6.4).

### 6.2 Mechanism

1. **Local write.** In one SQLite transaction, write the row and append a field-level patch
   `{table, id, fields: {name: value}, hlc}` to `outbox`.
2. **Push.** Batch the outbox and call the Postgres function `apply_changes(batch jsonb)` via Supabase RPC. It runs under the caller's
   RLS. For each field, it applies the value only if the incoming HLC is greater than `field_clocks[field]`. The
   operation is idempotent, so retries are safe.
3. **Pull.** For each table, `select … where server_seq > :cursor order by server_seq limit N`. RLS limits rows to
   spaces the user belongs to. The client merges with the **same LWW function**: implemented in TS in `@care/sync` and
   in plpgsql on the server, with a shared JSON file of test vectors that both implementations must pass.
4. **Triggers.** Sync runs on app foreground, after local writes (debounced), on a Supabase Realtime "poke" for the user's
   spaces (Realtime only signals; it carries no data), and on background tasks / silent pushes as best effort.

### 6.3 Details

- **HLC** = `(physical ms, counter, device id)` encoded as a sortable string. Tolerates clock skew and gives a deterministic total order.
- **Per-field, not per-row, LWW.** If one member renames the dog while another updates its weight, both changes survive. The
  `attributes` JSON is merged per key.
- **Deletes** are tombstones. A concurrent edit to another field does not resurrect a deleted row. "Restore" is an explicit action.
- **First sign-in / second device:** the device's local personal space is merged into the account's personal space. IDs are
  globally unique, so this is a re-parent with no duplicates.
- **Leaving or being removed from a space:** the client purges that space's local data on the next pull.
- **Photos** upload through a separate resumable queue. The log entry syncs immediately with `upload_state = pending`, and other
  members see a placeholder until the upload finishes.
- **Free users never sync.** If an account lapses from premium, sync pauses. Local data stays fully usable and exportable,
  and nothing is deleted from the server for a grace period (see open questions).

### 6.4 "Already done" and double completion

- **Online:** Today and notifications read `task_state`, which is refreshed by Realtime pokes. If someone else completed the
  occurrence, the item shows "Done by Sam at 8:02" and the local reminder is cancelled. Completing an already-done
  occurrence of a task marked `confirmDuplicate: true` (default for medication doses) shows a confirmation
  ("Sam already logged this dose at 8:02. Log another?").
- **Offline race:** both entries are kept (append-only means nothing is lost). When they sync, the engine detects two `done` entries
  for the same `occurrence_at`. The UI marks the occurrence with a **"Logged twice"** badge and sends a push to the space
  (medication tasks only, configurable). We never silently merge or drop one. The record must reflect what happened.

### 6.5 Testing sync

`@care/sync` is pure: merge function, HLC, batching, cursor bookkeeping. It's tested with property tests
(convergence: any delivery order and any duplication of the same patches gives the same state) and the shared
TS/SQL test vectors. Integration tests run against a local Supabase (`supabase start`) in CI.

### 6.6 Alternative: PowerSync

PowerSync (SQLite on device, streaming sync from Postgres, official Supabase integration) would replace steps 3–4 and
some client plumbing. Reasons I lean custom:
- Conflict resolution would *still* be ours to write (PowerSync forwards client writes to your own backend).
- It's another paid vendor in the critical path, and its SQLite binding replaces `expo-sqlite`.
- Our pull is a single indexed query per table, and our data is small.

Reasons to choose it: less code to own, and it handles partial-sync edge cases. Drizzle has a PowerSync driver, so if we
keep our repository layer behind Drizzle, switching at the start of Phase 3 costs days, not weeks. **This is open question
Q3.**

---

## 7. Module system

Full format in [`spec-modules-and-scheduling.md`](./spec-modules-and-scheduling.md). Highlights:

- A module is a JSON document: metadata, `attributes`, `taskTypes` (each with `configFields`, `logFields`,
  `defaultSchedule`, `effects`, reminder defaults, `quickLog` fields), `insights`, `careProfiles`, `disclaimers`,
  `migrations`, plus `minEngineVersion`.
- **Field types:** `number` (with `quantity` for unit conversion, `min`/`max` validation and separate `safeRange` for
  insights), `integer`, `boolean`, `text`, `longText`, `enum` / `multiEnum`, `date`, `datetime`, `duration`, `rating`,
  `member` (who gave it), `counter` (numeric attribute changed by effects).
- **Units:** values are stored in a canonical unit per quantity (g, mL, °C, cm, ppm…) and converted for display based on the user's
  metric/imperial setting. Concentrations like ppm need no conversion.
- **Validation** happens in two passes: structural (Zod) and semantic (refs resolve, expressions type-check against field types,
  IDs unique, backward compatibility with the previous version). Built-in modules are validated in CI. Remote modules are
  validated on the device before use, and invalid ones are ignored and reported.
- **Versioning:** integer `version`. Field/task IDs are immutable. Removing means `deprecated: true`. Changing a type means
  adding a new field plus a declarative migration op (`renameAttribute`, `convertUnit`, `mapEnum`, `setDefault`,
  `copyAttribute`). Migrations touch **attributes and task config/schedules only**, never log entries. Trackables pin
  `module_version` and are upgraded lazily on open, in a transaction.
- **Remote delivery:** a signed catalog (Ed25519 signature checked with `@noble/ed25519`) served from Supabase Storage/CDN.
  Modules whose `minEngineVersion` is newer than the app's engine are hidden until the app updates. This is data, not code, which is
  consistent with App Store guideline 2.5.2.
- **Custom modules** are produced by the visual builder in the same format, validated by the same validator, and stored per space.

---

## 8. Scheduling engine

Full rule format in the spec. Summary:

### 8.1 Rule kinds

| Kind | Example |
|---|---|
| `interval` | Every 3 days from last completion, or every 8 hours from a fixed anchor |
| `calendar` | 08:00 and 20:00 daily. First Monday of each month. 1 Apr and 1 Oct |
| `adaptive` (premium) | Base 7 days × 0.7 if `weather.tempMaxC3d > 28` + 2 days if `attr.potDiameterCm ≥ 25`, clamped to 3–21 days |
| `meter` | Due when a counter crosses a threshold (`config.supply ≤ config.refillAt`), or when `attr.odometer` has risen 5,000 since last completion |
| `anyOf` | Whichever comes first of several rules |

**Windowing** (`window`, `grace`, `earlyAllowance`) and **bounds** (`startsOn`, `until`, `count` — the course/finite case) are
modifiers that apply to *any* rule kind. "8am and 8pm for 10 days" is just `calendar` + `bounds`. This is cleaner than a
separate "course" kind.

### 8.2 Engine API (pure)

```ts
occurrences(rule, ctx, range): Occurrence[]              // expected occurrences in a range
status(task, logs, ctx): TaskStatus                      // state, dueAt, windowEnd, overdueSince, explain
nextDue(task, logs, ctx): Occurrence | null
matchLog(task, logs, newLog, ctx): OccurrenceRef | null  // which occurrence an ad-hoc log satisfies
planNotifications(tasks, ctx, budget): NotificationPlan  // rolling window, bundled
explain(task, ctx): Explanation                          // "Every 5 days instead of 7: hot week (−30%)"
```

`ctx` contains `now` (Instant), time zones, hemisphere, attributes, recent logs, an optional weather snapshot, and
`features` (premium flags). If adaptive scheduling isn't entitled, or its inputs are missing, the rule **degrades to
its base rule** and `explain` says why.

### 8.3 Time semantics (the part that usually goes wrong)

- **Wall-clock vs exact:** intervals in hours or minutes are exact durations (`Instant` arithmetic). Intervals in days, weeks or months and
  calendar times are *wall-clock* in the task's zone, so "8am daily" stays 8am across DST changes.
- **Time zone mode per task:** `space` (the home zone of the space; the default for plants, pets, tanks, home), `device`
  (follows the user when travelling; default for personal medication), or an explicit IANA zone.
- **DST gaps** (02:30 doesn't exist on spring-forward) resolve forward to 03:30. **DST overlaps** (01:30 happens twice) resolve
  to the earlier instant. Both follow Temporal's `compatible` disambiguation and are documented.
- **Offline for days:** nothing is "missed" in storage. On return, the engine derives the missed occurrences. Each task type has a
  `catchUp` policy: `latestOnly` (plants: show one overdue "Water") or `each` (records each missed medication dose for
  adherence, but **only the next dose is offered as actionable**; we never prompt to take several missed doses at once,
  a deliberate safety choice).
- **Server-side alerts** never run the engine. Clients sync the derived `next_due_at` / `alert_at`, and pg_cron + an edge
  function query `alert_at < now()` for tasks with missed-task alerts. Weather-adaptive inputs therefore never need to exist on
  the server.

### 8.4 Testing

- Table-driven tests across a time zone matrix: `America/New_York`, `Europe/London`, `Australia/Lord_Howe` (30-minute DST),
  `Asia/Kolkata` (+5:30, no DST), `Pacific/Chatham` (+12:45), `America/Sao_Paulo` (DST abolished 2019),
  `Pacific/Apia` (skipped 30 Dec 2011).
- Property tests (`fast-check`): occurrences strictly increasing, no duplicates; occurrences in [a,c) = occurrences in
  [a,b) ∪ [b,c); `nextDue` never earlier than the last completion for completion-anchored rules; determinism.
- Scenario tests: device offline 5 days, phone time zone change mid-course, edit a rule mid-course, completion just before or after
  a DST change.
- Coverage target for `scheduling`, `modules`, `sync`, `core`: ≥ 95% lines/branches. `insights` ≥ 90%.

---

## 9. Notifications

### 9.1 Local reminders (all tiers)

- **Platform limits:** iOS keeps at most **64** pending local notifications per app. Android caps alarms per app (~500) and,
  since Android 12/14, restricts *exact* alarms.
- **Rolling window:** `planNotifications` takes the next 7 days of occurrences across all tasks, **bundles** occurrences
  that fall in the same minute into one notification ("3 tasks due: Water fern, Feed Biscuit, …"), sorts them, and keeps the first
  *budget* items (iOS 60, Android 200). If the window would run out before the user is likely to open the app again, the
  last slot becomes "Open Care Tracker to keep reminders up to date".
- **Re-plan triggers:** app foreground, any log or task edit, sync pull, background task (`expo-background-task`, best
  effort), and notification receipt. The planner diffs against `scheduled_notifications` to avoid cancelling and recreating everything.
- **Actions:** notifications carry a **Done** action (and **Snooze 1h**, **Skip**). Completing from the notification takes one tap.
  Tasks with *required* quick-log fields open the compact log sheet with values prefilled from the last entry instead, which
  is the "under 2 taps" target. ⚠️ Handling an action while the app is fully killed is platform-sensitive in
  `expo-notifications`. Phase 2 starts with a spike; the fallback is opening the app straight to the prefilled sheet.
- **Exact alarms on Android:** reminders use inexact scheduling by default. Medication tasks offer to request
  `SCHEDULE_EXACT_ALARM`. We should **not** declare `USE_EXACT_ALARM` (Play restricts it to alarm/calendar apps).

### 9.2 Push (premium, shared spaces)

- Expo Push Service (one API for APNs/FCM). Tokens are stored in `push_tokens`.
- Events: "Sam logged the 8am dose", "Fern watering is overdue", "Dose logged twice", invite accepted.
- **Privacy default:** push and lock-screen text for medication modules is generic ("A scheduled task was completed in
  Home"). Details are opt-in per space. Payloads transit Apple/Google/Expo, so we minimize content.
- Silent "data changed" pushes trigger sync to cancel reminders that another member already completed (best effort; iOS
  throttles these).

---

## 10. Monetization

### 10.1 Tiers

| Feature | Free | Premium |
|---|---|---|
| Trackables | 3 (remote-configurable) | Unlimited |
| Built-in modules + care profiles | ✓ | ✓ + remote library |
| Fixed, calendar, windowed, course, meter rules | ✓ | ✓ |
| Adaptive rules (weather/season/attribute/log-driven) | Attribute/care-profile adjustments applied once at setup; otherwise the base rule | ✓ ongoing adaptation (ADR-014) |
| Local reminders, notification actions | ✓ | ✓ |
| Logging, notes, local photos | ✓ (photos stay on device) | ✓ + cloud photos |
| History list | ✓ | ✓ |
| Insights: trends, thresholds, adherence, streaks, digest | Teaser: last value + safe-range badge | ✓ |
| Custom module builder | ✓ (fields, tasks, non-adaptive schedules) | ✓ + adaptive rules, insight config |
| Cloud sync, multi-device | — | ✓ |
| Shared spaces, push, missed-task alerts | — | ✓ (see Q5: does the owner's plan cover members?) |
| PDF/CSV reports for a date range | — | ✓ |
| **Full data export (JSON + CSV) and account deletion** | **✓ always** | ✓ |

Why the custom builder is free: "track anything" is the core promise, the builder costs nothing to run, and the 3-trackable
limit already caps free usage. Gating it would make the free tier feel broken for exactly the users most likely to love the
product. Adaptive rules and insight configuration stay premium so the gating matches the engines.

Why export is always free: data portability is a legal obligation in some markets, and holding data hostage
damages trust, which matters more for a health-adjacent app.

### 10.2 Implementation

- RevenueCat with one entitlement, `premium`, and three products: monthly, annual, lifetime (non-consumable). The free
  tier works without any RevenueCat call. Purchases happen anonymously and are aliased to the Supabase user ID on sign-in.
- **The client gates UX. The server enforces cost-bearing features:** a RevenueCat webhook updates `entitlements`, and RLS / edge
  functions check it before sync writes, invites and photo uploads.
- **Downgrade behaviour:** never delete data. Trackables beyond the limit become read-only (still logged in history, can be
  archived or exported), adaptive rules fall back to base rules, sync pauses, shared spaces become read-only for that user.
- A small `entitlements` adapter with feature flags is built in **Phase 2**, so Phase 4 is wiring, not refactoring.

---

## 11. Privacy, security, legal and store-policy flags ⚠️

These need your attention, and some need a lawyer before launch. I'll keep flagging new ones as they come up.

**Health-adjacent data**
- Medication logs (and possibly pet medication) are **health data**. Under GDPR that's *special category* data: explicit consent is
  needed before we process it on our servers (i.e. at sync opt-in). In the US, the **FTC Health Breach Notification Rule**
  (2024 amendments explicitly cover health apps) and **Washington's My Health My Data Act** (and similar state laws) apply even though
  HIPAA does not. **Recommend a legal review before Phase 3 ships sync.**
- **No medical claims anywhere.** Built-in medication content contains no dosages or recommendations. The medication module
  shows a disclaimer when it's added and on its module page ("Not medical advice. Always follow your clinician's or vet's
  instructions. Reminders can fail; don't rely on this app alone for critical medications."). Insights on medication data are limited to
  adherence and logged values, with no "should" statements.
- **Correlations** are phrased as observations ("Nitrate readings were higher in weeks with daily feeding") and require minimum
  sample sizes.

**Data handling**
- Minimize what we collect: no account needed for free; no third-party analytics SDKs in MVP (see Q11); crash reporting
  (Sentry) with PII scrubbing, opt-out.
- Encryption in transit (TLS) and at rest (Supabase disk encryption; iOS Data Protection and Android file-based encryption
  on device). Optional **app lock** (biometric) is a cheap MVP addition. SQLCipher local encryption is open question Q7.
- Photos: strip EXIF (location) before upload. Private bucket with short-lived signed URLs.
- Account deletion **in-app** (Apple 5.1.1(v)) and via a **web URL** (Google Play requirement), implemented as an edge
  function. Shared spaces: ownership transfers to another admin. The deleted user's entries in shared spaces are anonymized
  to "Former member" (Q12).

**Store policies**
- **Apple 3.1.2 (subscriptions):** show price, period and renewal terms on the paywall, plus Restore Purchases and links to Terms and Privacy.
  Lifetime is a non-consumable.
- **Sign in with Apple** (or an equivalent privacy login) is required on iOS if we offer Google sign-in (guideline 4.8).
- **Google Play Health apps declaration** and **Data safety form**. **Apple privacy nutrition labels.** Medication
  features will draw review attention, so keep copy factual.
- **Location:** approximate only (iOS reduced accuracy, Android `ACCESS_COARSE_LOCATION`), "while in use" only, with a
  manual city entry alternative. Coordinates are rounded (~10 km) before being sent to our weather proxy and are not stored.
  The purpose string must explain the weather-adaptive feature.
- **Remote modules** are declarative data, not executable code (guideline 2.5.2).
- **Age rating / COPPA:** the app isn't directed at children, but caregivers may log a child's medication. That's fine as
  adult-entered data, but the privacy policy must say so.

---

## 12. Accessibility & performance

**Accessibility**
- Dynamic Type / font scaling everywhere, with layouts tested at the largest sizes (snapshot tests at 200%).
- Every chart has a text alternative: a summary sentence plus an accessible data table ("pH: last 7.4, safe 6.5–7.5,
  trending up over 14 days").
- State is never conveyed by colour alone. Overdue items get icon + text. Contrast is ≥ 4.5:1, verified by design tokens.
- Touch targets ≥ 44 pt. Screen reader labels and actions on Today items ("Mark done" as an accessibility action). Respect
  Reduce Motion.

**Performance**
- Cold start: Hermes, a small startup bundle, lazy routes for charts/builder/paywall, and Today read from the `task_state` cache
  instead of recomputing every schedule on launch (recomputed in the background after first paint).
- Budget: Today interactive < 1.5 s on a mid-range Android device, measured in CI on release builds from Phase 2.
- Completion: 1 tap from Today or the notification. 2 taps when required quick-log fields exist (prefilled).

**Widget-friendliness (later phase)**
- Whenever `task_state` changes, the app writes a small `next-due.json` snapshot to shared storage (iOS App Group / Android
  SharedPreferences). Widgets render that snapshot natively and never need a JS runtime. Same idea for Live Activities later.

---

## 13. Folder structure

```
care-tracker/
├── apps/
│   └── mobile/                     Expo app (expo-router)
│       ├── app/                    routes: (tabs)/today, trackables, settings; trackable/[id]; log/[taskId]; …
│       ├── src/
│       │   ├── services/           application services (completeTask, addTrackable, …)
│       │   ├── features/           today/ trackables/ logging/ builder/ spaces/ paywall/ settings/
│       │   ├── ui/                 design system: tokens, components, dynamic field renderers
│       │   ├── data/               drizzle schema, migrations, repositories
│       │   ├── sync/               outbox, transport, realtime poke
│       │   ├── notifications/      planner adapter, categories/actions, permissions
│       │   ├── entitlements/       feature flags, RevenueCat adapter
│       │   └── platform/           weather, location, files, sharing, print
│       └── test/
├── packages/
│   ├── core/                       ids (uuidv7), hlc, units, time wrapper, expr (AST, typecheck, eval)
│   ├── modules/                    schema (zod), json-schema generator, validator, interpreter, migrations
│   │   └── library/                built-in module JSON + care profiles (validated in CI)
│   ├── scheduling/                 rules, occurrences, status, matching, notification planning, explain
│   ├── insights/                   trends, thresholds, adherence, digest, report HTML/CSV builders
│   └── sync/                       merge/LWW, batching, test vectors shared with SQL
├── supabase/
│   ├── migrations/                 SQL schema, RLS policies, apply_changes()
│   ├── functions/                  revenuecat-webhook, push-dispatch, alerts-cron, weather, delete-account, invites
│   └── tests/                      pgTAP / integration tests
├── docs/                           architecture.md, spec-modules-and-scheduling.md, decisions.md, roadmap.md
├── .github/workflows/              ci.yml (typecheck, lint, test, validate modules), eas-build.yml
├── package.json  pnpm-workspace.yaml  tsconfig.base.json  vitest.workspace.ts
```

Tooling defaults (small decisions; I'll proceed unless you object): pnpm workspaces (`node-linker=hoisted` if RN tooling
needs it), TypeScript `strict` + `noUncheckedIndexedAccess` + `exactOptionalPropertyTypes`, ESLint (`eslint-config-expo`) +
Prettier, Vitest, Changesets *not* needed (packages aren't published), Conventional Commits.

---

## 14. Refined delivery plan

Each phase ends with working, tested software, an updated `roadmap.md`, and a short "what changed / what's next" summary.

**Phase 0 — Plan** *(this document)*
Exit: you approve the plan or request changes, and the open questions are answered.

**Phase 1 — Core engines (pure TS, no UI)**
- Spike (≤1 day): Temporal polyfill on Hermes (iOS + Android): correctness on the TZ matrix and performance.
- Monorepo scaffold and CI (typecheck, lint, test, coverage gates).
- `@care/core`: ids, HLC, units/conversion, time wrapper, expression AST + typechecker + evaluator.
- `@care/modules`: Zod schema, generated JSON Schema, semantic validator, interpreter (form models, defaults, effects),
  migration ops, compatibility checker.
- All 7 starter modules as JSON, validated in CI. Includes the flagged extensions (multi-instance tasks, counters/effects,
  meter, anyOf).
- `@care/scheduling`: all rule kinds (adaptive evaluated, but no weather source yet), status, matching, notification
  planner, explain. Full TZ/DST/offline suite.
- `@care/insights` basics: trend series + moving average, threshold checks, adherence and streaks.
- `@care/sync` merge core (needed now so the local schema is shaped right in Phase 2).
Exit: ≥95% coverage on engines; every starter module validates; a CLI script prints a 30-day agenda for a sample household.

**Phase 2 — Offline single-device app** *(split in two)*
- **2a:** Expo scaffold (dev builds), design tokens, SQLite + Drizzle, personal space, Add trackable (module → profile →
  attributes → confirm tasks), Today, Trackables list, Trackable detail (attributes/tasks/history), dynamic Log sheet,
  local notifications with rolling window + actions (spike first), entitlement flags stubbed (free limits enforced), settings
  (units, tz), JSON/CSV export.
- **2b:** Custom module builder v1, basic insights tab (trend chart + safe-range band), app lock, accessibility pass #1.
- Exit: internal TestFlight / Play internal testing build used daily for 1–2 weeks.

**Phase 3 — Accounts & sync**
Auth (Apple, Google, email magic link), Postgres schema + RLS, `apply_changes`, pull/push, Realtime pokes, local→account
merge, photo upload queue, account deletion (in-app + web), consent flow for health data. Legal review here.
Exit: two devices converge under the scripted chaos tests (offline edits, duplicates, reordering).

**Phase 4 — Monetization**
RevenueCat, paywall (honest copy, restore, lifetime), server-side entitlement enforcement, downgrade behaviour, App Store / Play
product setup. Exit: sandbox purchase, restore, upgrade, lapse and lifetime flows tested on both stores.

**Phase 5 — Shared spaces**
Invites (link/QR), roles, activity feed, "already done", double-completion detection, push via Expo, missed-task alerts
(pg_cron), per-member notification prefs. Exit: a 3-person household scenario test, plus a manual dogfood.

**Phase 6 — Smart scheduling & insights v2**
Weather proxy edge function (cached per rounded grid cell), coarse location permission flow, adaptive rules live with
"why this date" explanations, threshold-approach warnings, weekly digest, PDF/CSV reports, correlations (behind a flag), computed
fields. Exit: report snapshots reviewed; adaptive rules explainable for every starter module.

**Phase 7 — Polish & launch**
Widgets (iOS WidgetKit + Android via the snapshot file), onboarding, accessibility pass #2, performance budget sign-off,
store assets, privacy labels, Data safety form, health apps declaration, Terms/Privacy, launch checklist.

---

## 15. Open questions

Ordered by cost of reversing. My recommendation is in **bold**.

| # | Question | Recommendation |
|---|---|---|
| Q1 | **New repo.** I couldn't create it from this session (GitHub returned 403). Please create an empty repo (e.g. `SClawz/care-tracker`) and grant this app access, or tell me to keep working in `Da-Workshop`. Do you want the default branch named `main` or `master`? | **New private repo `care-tracker`, default branch `main`**. Everything in this folder moves to its root unchanged |
| Q2 | Confirm stack: Expo/RN/TS strict, pnpm monorepo, Supabase, RevenueCat | **Confirm** |
| Q3 | Sync: custom per-field LWW over Supabase, or PowerSync? | **Custom** (§6.6). Revisit at the start of Phase 3 with a 1-day PowerSync spike if you prefer |
| Q4 | Date library: Temporal polyfill, or Luxon? | **Temporal**, subject to the Phase 1 Hermes spike |
| Q5 | Shared spaces: does the owner's premium cover all members, or does each member need premium? | **Owner's plan covers up to 6 members**. Members get sync/sharing *within that space only* |
| Q6 | Custom module builder free or premium? | **Free** (adaptive rules and insight config inside it are premium) |
| Q7 | Local DB encryption (SQLCipher via `expo-sqlite`) in MVP? | **No.** Rely on OS encryption + optional biometric app lock. Revisit if medication becomes a primary segment |
| Q8 | Personal medication modelling: one trackable per medication (refill counters are natural) or one per person? This interacts with the 3-trackable free limit: someone on 4 medications hits the paywall immediately | **One trackable per person** ("My medications", "Mom") with `multi` dose tasks. Supply/refill counters live in task config. Avoids a health-related paywall wall |
| Q9 | Weather provider for adaptive rules: Open-Meteo (commercial plan) or Apple WeatherKit REST (needs attribution, works for Android via our proxy)? | **Open-Meteo commercial**, behind our proxy so we can switch |
| Q10 | Pricing points and trial (e.g. 7-day trial on annual)? | Decide by Phase 4. Not blocking |
| Q11 | Analytics: none, privacy-friendly aggregate (e.g. self-hosted PostHog with no PII), or opt-in only? | **No product analytics in MVP**; Sentry crash reports with scrubbing; revisit at launch |
| Q12 | Account deletion in shared spaces: anonymize the deleted user's log entries ("Former member") or delete them? | **Anonymize.** The records belong to the household's trackable history |
| Q13 | Grace period for server data after premium lapses (sync stops)? | **Keep 12 months**, then email before deleting; local data unaffected |
| Q14 | Launch markets / languages? | **US, CA, UK, EU, AU — English first** |
| Q15 | Should a minimal streak be free as an engagement hook (brief puts streaks in premium)? | **Yes, current streak free**; longest streak, adherence % and the rest premium |
| Q16 | Brand/product name and design direction (or should I propose a minimal design system)? | I'll propose tokens in Phase 2a unless you have a direction |
