# Spec: Module Definitions, Schedule Rules & Expressions

Status: **Proposed — Phase 0** · Companion to [`architecture.md`](./architecture.md)

The types below are the *intended shape* and will be implemented as Zod schemas in `@care/modules` and `@care/scheduling`.
A JSON Schema file (`module.schema.json`) will be generated from them for editor autocomplete and remote-module authoring.
Exact names may shift slightly during Phase 1. Any change will be recorded in `decisions.md`.

---

## 1. Module definition

```ts
interface ModuleDefinition {
  $schema?: string;                 // "https://…/module.schema.json" for editor support
  formatVersion: 1;                 // version of THIS spec
  minEngineVersion: string;         // semver of engine capabilities required, e.g. "1.0.0"
  id: string;                       // stable, e.g. "core.houseplant"; custom: "custom.<uuid>"
  version: number;                  // integer, monotonically increasing
  name: LocalizedText;              // "Houseplant" or { en: "Houseplant", de: "Zimmerpflanze" }
  description?: LocalizedText;
  icon: IconRef;                    // name in our bundled icon set; no remote images
  category: "pet" | "plant" | "aquatic" | "food" | "health" | "home" | "vehicle" | "other";
  disclaimers?: Disclaimer[];       // shown on add + module page (medication!)
  attributes: FieldDef[];           // describe the trackable itself
  taskTypes: TaskTypeDef[];
  insights?: InsightDef[];          // in addition to generic defaults
  careProfiles?: CareProfile[];
  migrations?: MigrationStep[];     // from previous versions of THIS module
}

interface Disclaimer { id: string; text: LocalizedText; showOn: ("add" | "module" | "report")[] }
```

### 1.1 Fields

Fields are used for trackable attributes, task config, and log values.

```ts
type FieldDef = {
  id: string;                       // camelCase, immutable forever
  label: LocalizedText;
  help?: LocalizedText;
  required?: boolean;
  deprecated?: boolean;             // hidden from new forms; old values still shown
  default?: JsonValue | Expr;       // literal or expression (e.g. last logged value)
} & (
  | { type: "number" | "integer";
      quantity?: Quantity;          // enables unit conversion; value stored in canonical unit
      unit?: string;                // for non-convertible units: "ppm", "dGH", "pills"
      min?: number; max?: number;   // input validation (hard)
      step?: number; decimals?: number;
      safeRange?: { min?: number; max?: number; warnMargin?: number }; // insights (soft), user-overridable
    }
  | { type: "counter"; unit?: string; min?: number }            // numeric, changed by effects
  | { type: "boolean" }
  | { type: "text"; maxLength?: number }
  | { type: "longText"; maxLength?: number }
  | { type: "enum" | "multiEnum"; options: { id: string; label: LocalizedText; deprecated?: boolean }[] }
  | { type: "date" | "datetime" }
  | { type: "duration"; min?: DurationSpec; max?: DurationSpec }
  | { type: "rating"; scale: 5 | 10 }
  | { type: "member" }                                           // space member or free-text name
  | { type: "computed"; valueType: "number"; expr: Expr; quantity?: Quantity; unit?: string } // Phase 6
);

type Quantity = "mass" | "volume" | "length" | "temperature" | "distance" | "duration" | "percent";
// Canonical units: g, mL, cm, °C, km, s, %. Display converts per user setting (metric/imperial).
```

Photos and notes are not fields. They're available on every log entry.

### 1.2 Task types

```ts
interface TaskTypeDef {
  id: string;                        // immutable
  name: LocalizedText;               // "Water", "Medication dose"
  icon: IconRef;
  cardinality: "single" | "multi";   // multi: several instances per trackable, each with own config
  configFields?: FieldDef[];         // per-instance settings (medication name, dose, supply)
  instanceName?: Expr;               // multi: display name, e.g. config.medicationName
  logFields: FieldDef[];             // recorded on completion
  quickLog?: string[];               // field ids shown inline on Today (≤ 2 recommended)
  defaultSchedule: ScheduleRule;
  catchUp: "latestOnly" | "each";    // how missed occurrences are presented (see architecture §8.3)
  confirmDuplicate?: boolean;        // warn if this occurrence is already done (default true for doses)
  reminder?: { enabled: boolean; offsets?: DurationSpec[]; critical?: boolean };
  effects?: Effect[];                // on completion
  actions?: SecondaryAction[];       // e.g. "Refill"
  alerts?: TaskAlert[];              // e.g. low supply
  suggested?: boolean;               // pre-checked in the Add flow (care profiles can override)
  adherence?: boolean;               // counts toward adherence (default true)
}

interface Effect { target: Ref; op: "add" | "set"; value: Expr; when?: Expr }   // target: attr.x | config.x (counter)
interface SecondaryAction { id: string; label: LocalizedText; logFields: FieldDef[]; effects?: Effect[] }
interface TaskAlert { id: string; label: LocalizedText; when: Expr; action?: string /* secondary action id */ }
```

`effects`, `actions` and `alerts` are the generic extensions flagged in architecture §3.3. They cover refills, stock counts,
odometers and filter hours with no category-specific code.

### 1.3 Insights

```ts
type InsightDef =
  | { kind: "trend"; field: FieldPath; window?: DurationSpec; movingAverage?: number }
  | { kind: "threshold"; field: FieldPath; range?: { min?: number; max?: number }; approachWarning?: boolean } // range defaults to field.safeRange
  | { kind: "adherence"; tasks?: string[] }                                  // default: all tasks with adherence=true
  | { kind: "streak"; task: string }
  | { kind: "distribution"; field: FieldPath }                               // enums / booleans
  | { kind: "correlation"; x: FieldPath | TaskFrequency; y: FieldPath; minSamples?: number } // later phase
  | { kind: "summary"; period: "week" | "month" };

type FieldPath = `${string}.${string}`;   // "<taskTypeId>.<logFieldId>" or "attr.<attributeId>"
```

**Generic defaults** apply to any field, including custom ones: numeric → trend + min/max/avg; boolean → % true;
enum → distribution; every task with `adherence !== false` → adherence + streak.

Insight *output* is structured data (`{ kind, severity, messageKey, params, series?, explain }`). The UI and the
report builder render it, and copy comes from localized templates, never free text from the engine.

### 1.4 Care profiles

```ts
interface CareProfile {
  id: string;                         // "pothos", "snake-plant"
  name: LocalizedText;
  description?: LocalizedText;        // always phrased as a starting point: "Typical starting point — adjust to your plant"
  attributeDefaults?: Record<string, JsonValue>;
  tasks?: Record<string /*taskTypeId*/, {
    suggested?: boolean;
    schedule?: ScheduleRule;          // replaces defaultSchedule
    configDefaults?: Record<string, JsonValue>;
  }>;
  safeRangeOverrides?: Record<FieldPath, { min?: number; max?: number }>;
}
```

### 1.5 Migrations

Module evolution is **append-only**: never remove or retype a field, task type or enum option. Deprecate and add instead.
Migration steps transform **trackable attributes and task config/schedules only**. Log entries are never rewritten.

```ts
interface MigrationStep {
  from: number; to: number;           // consecutive versions
  ops: (
    | { op: "renameAttribute"; from: string; to: string }          // old id becomes deprecated alias
    | { op: "copyAttribute"; from: string; to: string; convert?: { fromUnit: string; toUnit: string } }
    | { op: "mapEnum"; field: FieldPath; map: Record<string, string> }
    | { op: "setDefault"; field: FieldPath; value: JsonValue }
    | { op: "addTask"; taskType: string; onlyIf?: Expr }           // offered to the user, not forced
    | { op: "replaceDefaultSchedule"; taskType: string; onlyIfUnmodified: true }
  )[];
}
```

The validator checks that version N+1 is backward compatible with version N (no removed IDs, no type changes) and that the
migration chain from every shipped version reaches the latest one.

---

## 2. Schedule rules

```ts
type ScheduleRule = (IntervalRule | CalendarRule | AdaptiveRule | MeterRule | AnyOfRule) & RuleModifiers;

interface RuleModifiers {
  window?: DurationSpec;          // flexible: due any time within [dueAt, dueAt + window]
  grace?: DurationSpec;           // after window: "overdue" (soft) until grace ends, then "missed"/"overdue-hard"
  earlyAllowance?: DurationSpec;  // completion counts for this occurrence if up to this much early
  bounds?: {                      // course / finite
    startsOn?: LocalDate;         // inclusive, in the task's zone
    until?: LocalDate;            // inclusive
    count?: number;               // total occurrences (e.g. 20 doses = 10 days × 2)
  };
}

interface IntervalRule {
  kind: "interval";
  every: DurationSpec;            // { hours: 8 } exact | { days: 3 } wall-clock | { weeks: 2 } | { months: 1 }
  anchor: "completion" | "fixed"; // from last completion, or a fixed cadence from anchorAt
  anchorAt?: LocalDateTime;       // required for "fixed"
  at?: LocalTime;                 // optional snap-to time of day for day-or-longer intervals (e.g. 09:00)
}

interface CalendarRule {
  kind: "calendar";
  freq: "daily" | "weekly" | "monthly" | "yearly";
  interval?: number;              // every N freq units (default 1)
  byWeekday?: Weekday[] | { day: Weekday; nth: number }[];  // nth: 1..5, -1 = last
  byMonthDay?: number[];          // 1..31, -1 = last day
  byMonth?: number[];             // 1..12
  times: LocalTime[];             // ["08:00", "20:00"]
}
// RRULE-inspired subset; exportable to RFC 5545 for calendar export later.
// We do NOT use rrule.js (known TZ/DST issues); the evaluation is ours, on Temporal.

interface AdaptiveRule {
  kind: "adaptive";
  base: IntervalRule;
  adjust: {
    label: LocalizedText;         // shown in "why this date" explanations
    when: Expr;                   // boolean
    scale?: number;               // multiply interval (applied first, in order)
    add?: DurationSpec;           // then add/subtract (negative allowed)
  }[];
  clamp: { min: DurationSpec; max: DurationSpec };
  hysteresis?: DurationSpec;      // don't move an already-announced due date by less than this (default 12h)
}

interface MeterRule {
  kind: "meter";
  counter: Ref;                   // attr.x | config.x
  mode:
    | { type: "threshold"; lte?: Expr; gte?: Expr }        // supply ≤ refillAt
    | { type: "sinceLastCompletion"; delta: number };      // odometer +5000 since last done
}

interface AnyOfRule { kind: "anyOf"; rules: ScheduleRule[] } // whichever is due first

type DurationSpec = { minutes?: number; hours?: number; days?: number; weeks?: number; months?: number };
```

### 2.1 Task-level settings (not part of the rule)

```ts
interface TaskScheduling {
  rule: ScheduleRule;
  timeZoneMode: "space" | "device" | { zone: string /* IANA */ };
  pausedUntil?: Instant;          // vacation, plant dormant, etc.
}
```

### 2.2 Occurrence states

```
upcoming ──(dueAt − earlyAllowance)──► available ──(dueAt)──► due ──(dueAt + window)──► overdue
   ──(+ grace)──► missed (calendar / fixed anchor)  |  overdue-hard (completion anchor)
any state ──(log done)──► done        any state ──(log skipped)──► skipped
```

- For `anchor: "completion"` rules there is only ever **one** open occurrence, so offline gaps produce a single overdue item.
- For calendar and fixed-anchor rules, each past occurrence without a matching log is **missed** (derived). `catchUp` decides
  whether Today shows only the latest or lists each (medication shows each as missed, but only the next is actionable).
- **Matching an ad-hoc log** (logged from the trackable screen rather than from an occurrence): it satisfies the earliest
  open occurrence whose `available` time ≤ `performed_at` and that isn't missed; otherwise it's recorded as ad-hoc
  (`occurrence_at = null`) and doesn't affect adherence.

### 2.3 Time semantics

- `hours`/`minutes` durations use exact instant arithmetic. `days`/`weeks`/`months` and all `LocalTime`s are wall-clock in
  the task's zone.
- Nonexistent local times (DST gap) resolve **forward** by the gap length. Ambiguous ones (DST overlap) resolve to the
  **earlier** instant.
- `months` arithmetic clamps to the end of the month (31 Jan + 1 month = 28/29 Feb), then continues from the *original*
  day-of-month for fixed anchors (so it doesn't drift to the 28th forever).
- Changing a task's rule never rewrites history. Occurrences before the edit time are evaluated with the rule that was
  active then (tasks keep a small `scheduleHistory` of `{ rule, effectiveFrom }`).

---

## 3. Expression language

A typed JSON AST. There is no text parser in MVP (the visual builder emits the AST directly), no loops, no function definitions,
no property access beyond whitelisted refs, and no `eval`.

```ts
type Expr =
  | number | string | boolean | null
  | { ref: Ref }
  | { op: "and" | "or"; args: Expr[] }
  | { op: "not"; arg: Expr }
  | { op: "==" | "!=" | "<" | "<=" | ">" | ">="; args: [Expr, Expr] }
  | { op: "in"; args: [Expr, (string | number)[]] }
  | { op: "between"; args: [Expr, Expr, Expr] }        // inclusive
  | { op: "+" | "-" | "*" | "/"; args: [Expr, Expr] }   // x/0 → null
  | { op: "min" | "max"; args: Expr[] }
  | { op: "abs" | "round"; arg: Expr }
  | { op: "coalesce"; args: Expr[] };

type Ref =
  | `attr.${string}`                      // trackable attribute
  | `config.${string}`                    // this task's config
  | `log.${string}`                       // field of the log being recorded (effects/computed only)
  | `last.${string}.${string}`            // last logged value: last.<taskType>.<field>
  | `avg7d.${string}.${string}` | `avg30d.${string}.${string}`
  | `daysSince.${string}`                 // days since last completion of task type
  | "season" | "month" | "weekday" | "hemisphere"
  | "weather.tempC" | "weather.tempMaxC3d" | "weather.tempMinC3d" | "weather.humidityPct" | "weather.precipMm3d";
```

- **Static typing:** the validator infers a type for every node against the module's field definitions and rejects
  mismatches (e.g. comparing an enum to a number, or referencing an unknown field).
- **Null semantics:** missing data → `null`. Comparisons involving `null` are `false`, arithmetic with `null` is `null`.
  An adaptive adjuster whose `when` is `null`/`false` is skipped, and `explain` records "no data for weather".
- **Limits:** depth ≤ 16, nodes ≤ 256 per expression, enforced by the validator. Evaluation is a single tree walk with no
  recursion beyond the AST depth.
- **Capability refs:** `weather.*` requires the weather capability (premium + location permission). Without it, those
  refs evaluate to `null`.

---

## 4. Starter modules (sketches)

Full JSON for each is a Phase 1 deliverable. Two complete examples follow in §5.

| Module | Attributes | Task types (schedule) | Log fields | Insights |
|---|---|---|---|---|
| **Dog / Cat** (`core.pet`); profiles: Dog, Cat | species, breed, birthDate, sex, neutered, weight | Feeding (calendar 08:00, 18:00), Walk (dog only, calendar 3×/day), Medication dose (`multi`; config: name, dose, unit, supply?), Grooming (interval 4w), Vet visit (interval 12mo, window 1mo), Weigh-in (interval 1mo) | feeding: amount g, food type enum; walk: duration, distance?; dose: amount, given-by member; vet: reason, weight, notes; weigh-in: weight kg | weight trend, feeding/med adherence, walk streak |
| **Houseplant** (`core.houseplant`); profiles: Pothos, Snake plant, Monstera, Fiddle-leaf fig, Peace lily, ZZ plant, Spider plant, Succulent/Cactus, Boston fern | species profile, location, light enum, potDiameterCm, potMaterial enum (terracotta/plastic/ceramic), drainage bool | Water (adaptive: base per profile; summer ×0.8, winter ×1.4, terracotta ×0.85, pot ≥ 25 cm +2d, hot week ×0.75; window 1–2d), Fertilize (calendar monthly, Mar–Sep only), Repot (interval 18mo, window 3mo), Rotate (interval 2w, optional) | water: amount mL?, soilDryness enum; fertilize: product, dilution; repot: newPotDiameterCm (effect: set attr.potDiameterCm) | watering interval trend, adherence |
| **Freshwater Aquarium** (`core.aquarium-fw`); profiles: Tropical community, Coldwater/Goldfish, Planted | volumeL, heaterTarget °C, filterType, stocking notes | Water change (adaptive: base 7d, nitrate > 40 → ×0.6; window 2d), Water test (interval 7d), Filter clean (interval 4w, window 1w), Feed (calendar daily, optional, `adherence: false`) | change: percent; test: pH, ammonia ppm, nitrite ppm, nitrate ppm, tempC, GH/KH optional | thresholds on all test params with approach warnings, nitrate trend, correlation (later): feeding frequency → nitrate |
| **Sourdough Starter** (`core.sourdough`); profiles: Counter (room temp), Fridge | flourType, hydration target %, storage enum | Feed (profile: counter 12h / fridge 7d interval; adaptive on storage), Discard/bake (ad-hoc, `adherence: false`) | starter g, flour g, water g, flour type, roomTempC, riseHeightPct, hoursToPeak; computed hydration = water/flour×100 (Phase 6) | rise trend, temp vs hoursToPeak (later), feed streak |
| **Medication — personal** (`core.medication`); disclaimer required | person (name/"me"), notes | Dose (`multi`; config: medicationName, strength text, doseAmount, doseUnit, supply counter, refillAt; calendar default 08:00; bounds for courses; `confirmDuplicate`, `catchUp: each`; effect supply −= amount; action Refill (+= quantity); alert low supply) | amount, givenBy member, sideEffectNote? (free text, no interpretation) | adherence, streak, supply forecast ("~5 days left" — arithmetic, no advice) |
| **Home Maintenance** (`core.home`) | homeType, hasPets bool, hvacFilterSize | HVAC filter (interval 90d; 60d if hasPets, see note), Smoke/CO detector test (calendar monthly, 1st Sat), Gutters (calendar yearly byMonth [4, 10], window 3w), Water heater flush (interval 12mo), Dryer vent (interval 12mo) | filter: size, brand; detector: all OK bool, notes; gutters: notes | adherence, "next 90 days" summary |
| **Custom** (`core.custom`) | none (user adds) | none (user adds) | — | generic defaults |

*Note on HVAC + hasPets:* attribute-dependent intervals are exactly what `adaptive` expresses. To keep this **free**, care-profile
and **attribute-only** adjustments are resolved **once at task creation** (they seed the base interval), while **ongoing**
adaptation (season, weather, recent logs) is the premium feature. I recommend this split and record it as ADR-014.

---

## 5. Examples

### 5.1 Freshwater Aquarium (abridged but valid)

```json
{
  "formatVersion": 1,
  "minEngineVersion": "1.0.0",
  "id": "core.aquarium-fw",
  "version": 1,
  "name": "Freshwater Aquarium",
  "icon": "fish",
  "category": "aquatic",
  "attributes": [
    { "id": "volume", "label": "Tank volume", "type": "number", "quantity": "volume", "min": 1, "required": true },
    { "id": "heaterTarget", "label": "Heater set point", "type": "number", "quantity": "temperature" }
  ],
  "taskTypes": [
    {
      "id": "waterTest",
      "name": "Test water",
      "icon": "test-tube",
      "cardinality": "single",
      "catchUp": "latestOnly",
      "defaultSchedule": { "kind": "interval", "every": { "days": 7 }, "anchor": "completion", "window": { "days": 1 } },
      "logFields": [
        { "id": "ph", "label": "pH", "type": "number", "min": 0, "max": 14, "decimals": 1, "safeRange": { "min": 6.5, "max": 7.5, "warnMargin": 0.2 } },
        { "id": "ammonia", "label": "Ammonia", "type": "number", "unit": "ppm", "min": 0, "decimals": 2, "safeRange": { "max": 0.25 } },
        { "id": "nitrite", "label": "Nitrite", "type": "number", "unit": "ppm", "min": 0, "decimals": 2, "safeRange": { "max": 0.25 } },
        { "id": "nitrate", "label": "Nitrate", "type": "number", "unit": "ppm", "min": 0, "safeRange": { "max": 40, "warnMargin": 10 } },
        { "id": "temp", "label": "Temperature", "type": "number", "quantity": "temperature", "safeRange": { "min": 24, "max": 27 } }
      ]
    },
    {
      "id": "waterChange",
      "name": "Water change",
      "icon": "bucket",
      "cardinality": "single",
      "catchUp": "latestOnly",
      "quickLog": ["percent"],
      "defaultSchedule": {
        "kind": "adaptive",
        "base": { "kind": "interval", "every": { "days": 7 }, "anchor": "completion" },
        "adjust": [
          { "label": "High nitrate at last test", "when": { "op": ">", "args": [{ "ref": "last.waterTest.nitrate" }, 40] }, "scale": 0.6 }
        ],
        "clamp": { "min": { "days": 3 }, "max": { "days": 14 } },
        "window": { "days": 2 }
      },
      "logFields": [
        { "id": "percent", "label": "Water changed", "type": "number", "quantity": "percent", "min": 0, "max": 100, "default": 25 }
      ]
    }
  ],
  "insights": [
    { "kind": "threshold", "field": "waterTest.ph", "approachWarning": true },
    { "kind": "threshold", "field": "waterTest.nitrate", "approachWarning": true },
    { "kind": "trend", "field": "waterTest.nitrate", "movingAverage": 3 }
  ],
  "careProfiles": [
    { "id": "tropical", "name": "Tropical community" },
    { "id": "coldwater", "name": "Coldwater / goldfish",
      "safeRangeOverrides": { "waterTest.temp": { "min": 18, "max": 22 }, "waterTest.ph": { "min": 7.0, "max": 8.0 } } }
  ]
}
```

### 5.2 Medication dose task type (excerpt)

```json
{
  "id": "dose",
  "name": "Dose",
  "icon": "pill",
  "cardinality": "multi",
  "instanceName": { "ref": "config.medicationName" },
  "catchUp": "each",
  "confirmDuplicate": true,
  "configFields": [
    { "id": "medicationName", "label": "Medication", "type": "text", "required": true },
    { "id": "doseAmount", "label": "Usual amount", "type": "number", "min": 0 },
    { "id": "doseUnit", "label": "Unit", "type": "enum", "options": [
      { "id": "tablet", "label": "tablet(s)" }, { "id": "capsule", "label": "capsule(s)" },
      { "id": "ml", "label": "mL" }, { "id": "drop", "label": "drop(s)" }, { "id": "puff", "label": "puff(s)" }, { "id": "unit", "label": "unit(s)" } ] },
    { "id": "supply", "label": "Remaining", "type": "counter", "min": 0 },
    { "id": "refillAt", "label": "Remind me to refill at", "type": "number", "min": 0 }
  ],
  "defaultSchedule": { "kind": "calendar", "freq": "daily", "times": ["08:00"], "grace": { "hours": 2 } },
  "logFields": [
    { "id": "amount", "label": "Amount", "type": "number", "min": 0, "default": { "ref": "config.doseAmount" } },
    { "id": "givenBy", "label": "Given by", "type": "member" }
  ],
  "quickLog": [],
  "effects": [
    { "target": "config.supply", "op": "add", "value": { "op": "-", "args": [0, { "ref": "log.amount" }] },
      "when": { "op": "!=", "args": [{ "ref": "config.supply" }, null] } }
  ],
  "actions": [
    { "id": "refill", "label": "Refill",
      "logFields": [{ "id": "quantity", "label": "Quantity added", "type": "number", "min": 0, "required": true }],
      "effects": [{ "target": "config.supply", "op": "add", "value": { "ref": "log.quantity" } }] }
  ],
  "alerts": [
    { "id": "lowSupply", "label": "Refill soon",
      "when": { "op": "<=", "args": [{ "ref": "config.supply" }, { "ref": "config.refillAt" }] }, "action": "refill" }
  ]
}
```

The module-level `disclaimers` for `core.medication` are shown on add, on the module page and on every report.
