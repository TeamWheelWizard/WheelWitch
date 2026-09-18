# WheelWitch Cleanup Plan

Readability, maintainability, bugs, and Android best-practices review of the
whole repo. Ordered by priority. Stage 1 fixes bugs; Stage 2 reshapes
structure; Stage 3 is polish.

---

## Stage 1 — Bugs (fix first, highest impact)

**B1. `FileDownloader.activeChunks` never decremented**
`util/io/FileDownloader.kt:242` initialises `activeChunks = ranges.size` but no
worker ever decrements it. Progress always reports "N/N chunks" until the
terminal emit zeroes it. The KDoc promises an "X of N chunks" indicator that is
broken.
*Fix:* decrement `activeChunks` in each chunk worker's completion path (and on
retry-exhaustion failure), or drop the field entirely if the UI never needs
per-chunk granularity.

**B2. `LogExporter.TIMESTAMP_FORMAT` non-thread-safe**
`util/log/LogExporter.kt:25` is a shared `SimpleDateFormat` field.
`SimpleDateFormat` is not thread-safe; concurrent `flushToCacheFile` calls race.
*Fix:* replace with thread-safe `java.time.format.DateTimeFormatter`
(minSdk 31 allows it), or make it a local.

**B3. `RewindPackManager` swallows `CancellationException`**
`domain/RewindPackManager.kt:92,114,154` wraps coroutine bodies in `runCatching`,
which catches `CancellationException` and converts it to `Result.failure`.
Navigating away mid-install corrupts cooperative cancellation.
*Fix:* rethrow `CancellationException` before the general handler (explicit
try/catch with a cancellation rethrow first).

**B4. `mapObjects` helper throws despite its docstring**
`util/json/JsonExtensions.kt` `mapObjects` is documented to skip non-object
elements but calls `getJSONObject`, which throws. One malformed row breaks
`RaceStatsParser` / `LeaderboardParser`.
*Fix:* use `optJSONObject` with a null-skip in the loop so a single bad element
can't fail the whole parse.

**B5. `OnlineViewModel.fetchRaceStats` reads SharedPreferences on main thread**
The failure path calls `loadRaceStatsCache()` synchronously (off-IO) — ANR risk
under memory pressure.
*Fix:* wrap the fallback cache read in `withContext(ioDispatcher)` (inject the
dispatcher; see P4).

**B6. `PulsingDot` runs infinite animation unconditionally**
`ui/components/PulsingDot.kt` creates `rememberInfiniteTransition` even when
`pulse=false`, wasting frame work. Also doesn't respect reduced-motion.
*Fix:* gate the infinite transition on `pulse`; respect `LocalReduceMotion`
(see P3).

**B7. `OptionalFileTree` ignores rename-rotation failure**
`util/io/OptionalFileTree.kt` ignores `file.renameTo()`'s return value;
rotation is silently lost on failure and the file grows unbounded.
*Also:* the rotation bound uses char length, not UTF-8 byte length, so
non-ASCII lines can exceed the 1MB cap.
*Fix:* check the rename result and log/fall back to truncating; bound on byte
length.

---

## Stage 2 — Readability & Maintainability

**R1. Break up `SaveDataViewModel` (God ViewModel)**
618 lines, 11+ state flows, 17 constructor params. Handles parse, multi-region,
leaderboard merge, badges, backup/restore/delete (9 ops), timestamps, score
computation, and rating VR.
*Fix:* split into `SaveParseViewModel` + `BackupViewModel`, or extract the
leaderboard-merge and backup logic into injected use-case classes the VM
delegates to. Consolidate the six saver lambdas into one injected `SaveManager`.

**R2. Fix stale coroutine races in `SaveDataViewModel.refresh()`**
Fire-and-forget badge/leaderboard launches aren't cancelled on re-refresh; stale
responses can overwrite fresh state. `refreshIfStale` has a TOCTOU race.
*Fix:* keep a `Job` handle and cancel prior work, or fold the sub-requests into
one cancellable `coroutineScope { awaitAll }`; make staleness a guarded
atomic check-set.

**R3. Log line format single-source-of-truth**
`OptionalFileTree.formatLine`, `LogExporter.buildReport`, and the
`LogTextRenderer` regex each independently encode `<ts> <LEVEL>/<tag>: msg`.
Any drift silently breaks colouring.
*Fix:* add `LogEntry.serialize()` and reuse everywhere; keep the regex in
`LogTextRenderer` but test it against the serializer.

**R4. Break up `DolphinTree` (1149 lines)**
Handles SAF tree, ROM copy, zip extraction, launch descriptor, config INI,
version file, game INI management, and URI permissions.
*Fix:* extract the INI-manipulation helpers (`addIniKeyValue`,
`removeIniKeyInSection`, `renameSection`, `extractIniSection`) into a separate
file/class (mirroring the `DolphinConfig` pattern). Extract the ~70-line
`ensureRmcGameInis` (does three unrelated things) into named helpers.

**R5. `Rank.kt` mixes model + domain logic**
`model/Rank.kt` holds computation (`computeScore`, `computeNeeds`, etc.) and
magic constants alongside data models. Per AGENTS.md, `model/` = data types,
`domain/` = business logic.
*Fix:* move the scoring functions + constants to `domain/`, keep data classes in
`model/`. Use immutable `List`/`DoubleArray` copies instead of mutable
`val Array`; replace stringly-typed `omit` with an enum/sealed; de-duplicate the
`computeNeeds`/`computeScore` math. Note the pre-release comparison in
`SemVersion` is also lexicographic (numeric identifiers like `beta10` sort
before `beta2`).

**R6. Merge duplicated model types**
- `LeaderboardEntry` ↔ `PlayerLeaderboardData` overlap heavily (name/vr/miiData)
  — merge or express one in terms of the other.
- `RaceStats.kt`: `NamedStat`, `TrackStat`, `DayStat` are structurally identical
  (name + raceCount) — collapse to one type.
*Fix:* one shared type per shape.

**R7. Deduplicate `RewindPackManager` install flow**
`installLatest`/`update`/`reinstall` are near-identical (only the URL/steps
differ; version-write + metadata logic is tripled).
*Fix:* a single `runInstall(urlResolver)` helper plus step computation.

**R8. Deduplicate network fetch logic**
`VersionFileParser.fetchUrl` and `GitHubReleaseParser.fetchUrl` are duplicated,
with a null-guard divergence (`GitHubReleaseParser` has an NPE risk — missing
`.body` null guard).
*Fix:* one shared `fetchUrl` helper; add a User-Agent header for the GitHub API
(403 without one).

**R9. Consistent `@Immutable` + indentation across models**
`@Immutable` is applied inconsistently across model files (and across classes
within a single file). ~7 model files use 2-space indentation, violating ktfmt
DEFAULT (4-space).
*Fix:* annotate all Compose-consumed model classes; run `spotlessApply`.

**R10. Remove dead/duplicated constants**
`ONBOARDING_TRANSITION_MS` is defined but unused in `OnboardingScreen.kt` and
duplicated in `MainScreen.kt`. `MemoryInfo.usagePercent`/`total` are always
null. `SaveManager.backup()` writes a dead `bytes = -1L` field.
*Fix:* delete or consolidate.

**R11. AGENTS.md drift**
Docs say "Java 11"; build targets JVM 17. Docs list `RR_BASE`/`RWFC_API`/
`BADGES_BASE`/`ROM_EXTENSIONS` as externally accessible, but they're `private`.
*Fix:* update AGENTS.md; either make constants public or fix the docs.

---

## Stage 3 — Android Best Practices

**P1. Hardcoded user-facing strings**
- `RankBadge.kt:150` — `"$pointsNeeded pts to "`
- `RaceStatsScreen.kt` — `"12a"`, `"12p"`, `"${hour}a"`, `"${hour-12}p"`
- `TopBar.kt:190` — `"$version CANARY"`
- `HomeScreen.kt` — `"\u2022 "` bullet prefix concatenation (x2)
- `TimeTrialScreen.kt` — `"#"` prefix, `"-"` placeholder
- `OnboardingScreen.kt:446` — newline-concatenated title (one formatted resource)
*Fix:* move all to `res/values/strings.xml`, use formatted resources.

**P2. Security: `allowBackup="true"` + SAF grant**
`allowBackup` true means the persisted Dolphin SAF tree URI grant (which could
point at arbitrary user storage) may be extractable via backup. Sideloaded app
handling storage grants.
*Fix:* verify `backup_rules.xml` / `data_extraction_rules.xml` exclude the
persisted tree URI + prefs; add exclusions if not.

**P3. Accessibility**
- "Joinable"/"Open" badges: white on `0xFF4CAF50` ≈ 2.9:1 contrast, below WCAG
  AA 4.5:1 (`RoomDetail`, `RoomsScreen`).
- `TimeTrialScreen` filter chips: custom `Surface.clickable` with no
  `selectable` role semantics and no gamepad focus border.
- `RaceStatsScreen` charts: no contentDescription/semantics — data invisible to
  screen readers.
- `PulsingDot`/`SparkleHat`: should respect `LocalReduceMotion`.
*Fix:* darken the ok-green / use an `on*Container` pair; convert chips to
`FilterChip` or add `selectable` semantics + focus border; add chart semantics;
gate animations on reduced-motion.

**P4. Testability: inject dispatchers consistently**
`MiiMakerViewModel` and `OnlineViewModel` hardcode `Dispatchers.IO` while every
other VM injects `ioDispatcher`.
*Fix:* constructor-inject the dispatcher (also unlocks the B5 fix).

**P5. Shared-transition key magic strings**
`"online_title_Rooms"` etc. are duplicated across `OnlineMenuScreen` and each
destination screen — fragile coupling; a rename silently breaks the shared
transition.
*Fix:* centralise the keys on the `OnlineMenuPage` enum.

**P6. Remove `libs.material` if unused**
Legacy Material Components dependency alongside Compose Material3 is likely
unnecessary.
*Fix:* verify and drop if nothing uses it.

**P7. `RewindPackManager` inject collaborators**
References `Context` directly and calls `VersionFileParser` object statics,
forcing `mockkObject`/`mockkStatic`. Inconsistent with `SaveDataViewModel`'s
constructor-injection pattern.
*Fix:* inject a fetcher/interface + a cache-dir provider.

---

## Suggested order

Stage 1 bugs (B1–B7) → Stage 2 structure (R1–R11) → Stage 3 practices (P1–P7).
Bugs are isolated quick wins; structure is the largest effort; practices are
polish.

---

## Completed

### Startup crash fix
- `902ba1e` fix(viewmodel): restore factory construction for AndroidViewModel — added companion `Factory` to `MiiMakerViewModel` and `OnlineViewModel`; wired both into `MainScreen`'s bare `viewModel()` calls. (Root cause: P4 had removed the synthetic `(Application)` constructor.)

### Stage 3 practices (P1–P7)
- `ed61856` P1 i18n strings
- `0e7bd6c` P3 accessibility (badges, chips, chart semantics, reduced-motion)
- `3c1597b` P4 dispatcher injection
- `b1eb985` P5 shared-transition keys
- `0e5d554` P7 collaborator injection into RewindPackManager

### Post-launch cleanup (this session)
- `46ea887` refactor(data): memoise DolphinTree.fromPersisted — process-local memo invalidated by persist()/grant-loss; cuts startup SAF tree rebuilds from 2-4 to 1; includes a regression test.
- `bff5f14` chore(deps): drop com.google.android.material — removed `libs.material` and swapped the theme parent to `android:Theme.Material.NoActionBar`.
- `8744f9a` fix(security): exclude SAF tree URI from backup — removed `wheelwitch.xml` from `backup_rules.xml` and `data_extraction_rules.xml` (it holds only the persisted SAF tree URI; onboarding state stays in `settings.xml`).
- Back-callback warning (`enableOnBackInvokedCallback="true"` already set at AndroidManifest.xml:24) investigated: fires transiently when Compose `BackHandler` callbacks deregister between overlay transitions; benign, back navigation works. No code change.
