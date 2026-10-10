# Spec: Timers, Routines, Schedules, Templates & Insights

Status: **Proposed — Phase 0 (rev 2)** · Companion to [`architecture.md`](./architecture.md)

These are the intended shapes. They'll be implemented as Zod schemas in the pure packages, with JSON Schema generated for
templates. Names may shift during Phase 1, and changes will be recorded in `decisions.md`.

---

## 1. Shared types

```ts
type DurationSpec = { hours?: number; minutes?: number; seconds?: number; days?: number; weeks?: number; months?: number };
type LocalTime = `${number}:${number}`;     // "08:00"
type LocalDate = string;                     // "2026-10-10"
type Instant = number;                       // epoch ms, UTC
type LocalizedText = string | Record<string, string>;
type CueRef = "chime" | "bell" | "beep" | "whistle" | "none" | `custom:${string}`;
```

---

## 2. Timer plans (`@rt/timer`)

```ts
type TimerPlan = CountdownPlan | StopwatchPlan | SequencePlan | GroupPlan;

interface CountdownPlan {
  kind: "countdown";
  duration: DurationSpec;
  overtime?: boolean;            // keep counting up after zero (default true)
  endCue?: CueRef;
  repeat?: number;               // restart N times automatically (e.g. "every 10 min, 6×")
}

interface StopwatchPlan {
  kind: "stopwatch";
  laps?: boolean;
  cap?: DurationSpec;            // safety auto-finish (default 12h)
  milestones?: DurationSpec[];   // optional cues at e.g. 15m, 30m
}

interface SequencePlan {
  kind: "sequence";
  items: (PhaseDef | RepeatBlock)[];
  autoAdvance?: boolean;         // default true for timed phases
}
interface RepeatBlock { repeat: number; items: (PhaseDef | RepeatBlock)[]; skipLastRest?: boolean }
interface PhaseDef {
  label: LocalizedText;          // "Work", "Rest", "Boil pasta"
  duration?: DurationSpec;       // absent → manual advance ("until done")
  tone: "prep" | "work" | "rest" | "other";
  cue?: CueRef;                  // played at phase start
  countdownCue?: number;         // beep for the last N seconds (e.g. 3)
}

interface GroupPlan {
  kind: "group";
  align: "startTogether" | "finishTogether";
  members: { label: LocalizedText; plan: CountdownPlan | SequencePlan; offset?: DurationSpec }[];
}
```

Validation limits: ≤ 500 expanded phases, nesting depth ≤ 4, group size ≤ 12, durations from 1 s to 7 days.

### 2.1 Events and projection

```ts
type TimerEvent =
  | { t: "start"; at: Instant }
  | { t: "pause"; at: Instant } | { t: "resume"; at: Instant }
  | { t: "advance"; at: Instant } | { t: "back"; at: Instant }
  | { t: "adjust"; at: Instant; deltaMs: number; scope: "phase" | "total" }
  | { t: "lap"; at: Instant }
  | { t: "memberStart"; at: Instant; member: number }          // group, staggered member actually started
  | { t: "ack"; at: Instant }                                  // ringing dismissed
  | { t: "finish"; at: Instant } | { t: "cancel"; at: Instant }
  | { t: "clockCorrection"; at: Instant; deltaMs: number }
  | { t: "editTimes"; at: Instant; startedAt?: Instant; endedAt?: Instant }; // after-the-fact fix

interface TimerProjection {
  status: "running" | "paused" | "ringing" | "finished" | "cancelled";
  phaseIndex: number; phaseLabel?: LocalizedText; phaseTone?: string;
  phaseElapsedMs: number; phaseRemainingMs: number | null;   // null = manual phase or stopwatch
  activeElapsedMs: number; totalRemainingMs: number | null;
  overtimeMs: number;
  laps: { at: Instant; splitMs: number; lapMs: number }[];
  members?: TimerProjection[];                                // groups
}

project(plan: ExpandedPlan, events: TimerEvent[], now: Instant): TimerProjection
boundaries(plan, events, now, horizonMs): { at: Instant; kind: "phaseEnd" | "end" | "memberStart" | "milestone";
                                             phaseIndex?: number; member?: number; label?: LocalizedText }[]
```

Invariants (property-tested):
- `activeElapsed` is non-decreasing in `now` while running, and constant while paused.
- `boundaries` are strictly increasing, and projecting at `b.at` reports the phase after `b`.
- Replaying any prefix of events and then the rest gives the same result as replaying everything at once.

---

## 3. Routines

```ts
interface Routine {
  id: string;
  name: LocalizedText; icon: string; color: string; tags: string[];
  steps: Step[];                       // ≥ 1
  fields?: FieldDef[];                 // recorded on finish (e.g. mood, notes are always available)
  schedule?: ScheduleRule;             // absent = on-demand only
  timeZoneMode?: "device" | { zone: string };  // default device
  reminder?: { enabled: boolean; offsets?: DurationSpec[] };
  countsFrom?: { presetIds?: string[]; tags?: string[] };   // standalone timer sessions that count toward this routine
  flow?: "guided" | "checklist";       // guided = one step at a time; checklist = all steps visible
}

type Step = { id: string; label: LocalizedText; optional?: boolean } & (
  | { type: "check" }
  | { type: "countdown"; plan: CountdownPlan; autoAdvance?: boolean }
  | { type: "stopwatch"; plan: StopwatchPlan }
  | { type: "sequence"; plan: SequencePlan }
  | { type: "input"; fields: FieldDef[] }
);
```

### 3.1 Fields

These are kept from rev 1, without counters or units conversion beyond the basics.

```ts
type FieldDef = { id: string; label: LocalizedText; required?: boolean; deprecated?: boolean; default?: JsonValue } & (
  | { type: "number" | "integer"; unit?: string; min?: number; max?: number; decimals?: number }
  | { type: "boolean" } | { type: "text"; maxLength?: number } | { type: "longText" }
  | { type: "enum" | "multiEnum"; options: { id: string; label: LocalizedText }[] }
  | { type: "rating"; scale: 5 | 10 } | { type: "duration" }
);
```

---

## 4. Schedule rules (`@rt/schedule`)

```ts
type ScheduleRule = (IntervalRule | CalendarRule | QuotaRule) & {
  window?: DurationSpec;          // flexible completion window after due
  grace?: DurationSpec;           // overdue (soft) until grace ends, then missed
  earlyAllowance?: DurationSpec;
  bounds?: { startsOn?: LocalDate; until?: LocalDate; count?: number };  // course / challenge ("30-day")
};

interface IntervalRule { kind: "interval"; every: DurationSpec; anchor: "completion" | "fixed"; anchorAt?: string; at?: LocalTime }
interface CalendarRule {
  kind: "calendar"; freq: "daily" | "weekly" | "monthly" | "yearly"; interval?: number;
  byWeekday?: (Weekday | { day: Weekday; nth: number })[]; byMonthDay?: number[]; byMonth?: number[];
  times?: LocalTime[];            // absent = any time that day (due by end of day)
}
interface QuotaRule {             // goals: "3 h per week", "4 sessions per week", "20 min per day"
  kind: "quota";
  period: "day" | "week" | "month";
  weekStartsOn?: Weekday;
  target: { sessions?: number; duration?: DurationSpec };
  nudgeAt?: LocalTime;            // reminder on the period's last day if not yet met
}
```

**Semantics.** These carry over from rev 1:
- Hour and minute intervals are exact durations. Day and longer intervals are wall-clock.
- DST gaps resolve forward, and overlaps resolve to the earlier instant.
- Months clamp to the end of the month without drifting.
- Edits apply from their effective time, using a `scheduleHistory`.
- Being offline for days produces one overdue item for completion-anchored rules, and derived "missed" occurrences for calendar rules.
- **Quota progress** is the sum of sessions linked to the routine (including `countsFrom` timer sessions) within the period, split at
  period boundaries in local time.

**Engine API.**
- `occurrences(rule, ctx, range)`
- `status(routine, sessions, ctx)` returns `{ state, dueAt, windowEnd, progress?, explain }`
- `matchSession(...)`
- `quotaProgress(...)`

---

## 5. Templates (`@rt/templates`)

```ts
interface Template {
  formatVersion: 1;
  minEngineVersion: string;
  id: string;                      // "core.pomodoro"
  version: number;
  kind: "timer" | "routine" | "bundle";   // bundle = routine + presets that count toward it
  name: LocalizedText; description?: LocalizedText; icon: string; color?: string;
  category: "focus" | "fitness" | "cooking" | "home" | "selfcare" | "learning" | "care" | "other";
  tags?: string[];
  notes?: LocalizedText[];         // e.g. exercise-at-own-risk line for fitness templates
  params?: TemplateParam[];        // asked at instantiation: "Work length", "Rounds"
  preset?: { name: LocalizedText; plan: TimerPlan };          // kind timer / bundle
  routine?: Omit<Routine, "id">;                               // kind routine / bundle
  premium?: boolean;               // extended library
}
interface TemplateParam { id: string; label: LocalizedText; type: "duration" | "integer" | "time" | "weekdays"; default: JsonValue; min?: number; max?: number }
```

Parameters are substituted by `instantiate(template, params)` through `{ "param": "workLength" }` placeholders. This is plain substitution:
there is no expression language in MVP. The result is validated and then copied into a user-owned routine or preset.

### 5.1 Starter templates

| Template | Kind | What it proves |
|---|---|---|
| **Pomodoro** (25/5 ×4, then 15) | timer (+ optional "Deep work 10 h/week" quota bundle) | nested repeats, skipLastRest, quota linking |
| **Tabata / HIIT** (prep 10s, 20/10 ×8, cool-down) | timer | fast phases, countdown cues, notification budget |
| **Pasta night** (pasta 10m, sauce 15m, garlic bread 8m) | timer group, finishTogether | staggered starts, group alarms |
| **Tea / egg / laundry quick countdowns** | timer | simple countdown presets, overtime |
| **Morning routine** (water ✓, stretch 5m, meditate 10m, journal ✓, mood) | routine, daily 07:00 | mixed steps, guided flow, fields |
| **Practice tracker** (stopwatch + "what I practised") | bundle: preset + 3 h/week quota | stopwatch → quota, laps |
| **Weekly chores** (vacuum, bathrooms, laundry; optional time tracking) | routine, weekly checklist | checklist flow, window |
| **Plant watering / pet feeding** | routine, interval / calendar | the old care use cases as plain routines |
| **Reading habit** (20 min daily) | routine, single countdown step | single-step routine, streaks |
| **Blank routine / blank timer** | — | builder entry points |

---

## 6. Insights (`@rt/insights`)

```ts
type InsightQuery = {
  range: { from: LocalDate; to: LocalDate }; zone: string;
  filter?: { routineIds?: string[]; presetIds?: string[]; tags?: string[]; kinds?: ("routine" | "timer")[] };
};

timePerBucket(sessions, q, bucket: "day" | "week" | "month", groupBy?: "preset" | "routine" | "tag") → StackedSeries
heatmapCalendar(sessions, q, metric: "duration" | "count") → CalendarCells
heatmapWeekHour(sessions, q) → number[7][24]
durationHistogram(sessions, q, bins?) → Histogram
streaks(routine, sessions, ctx) → { current, longest, history }
adherence(routine, sessions, ctx, q) → { onTimeRate, doneRate, missedCount, byWeekday }
quotaHistory(routine, sessions, ctx, q) → { period, progress, target, met }[]
stepBreakdown(routine, sessions, q) → { stepId, avgMs, share }[]
overrun(sessions, q) → { rate, avgOvertimeMs }
fieldTrend(sessions, fieldId, q, movingAverage?) → Series
records(sessions, q) → { longestSession, bestWeek, longestStreak }
summarize(result) → { messageKey, params }   // accessible text + report sentence
```

Every output is plain data. Charts, reports, widgets and (later) a web app render the same series.
