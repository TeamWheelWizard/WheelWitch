# WheelWitch

An Android app that downloads/updates the Retro Rewind Mario Kart Wii Pack and launches Dolphin Emulator.

> Build instructions, commit conventions, and contribution guidelines live in [CONTRIBUTING.md](CONTRIBUTING.md). This file covers architecture, key decisions, constants, and testing: the things that don't change often.

## Build & Dev

See [CONTRIBUTING.md#build](CONTRIBUTING.md#build) for build commands and signing setup.

### Formatting (Spotless + ktfmt)

- `./gradlew spotlessApply`: auto-format all `.kt` and `.kts` files
- `./gradlew spotlessCheck`: verify formatting (for CI)
- No configuration to debate; ktfmt DEFAULT style is enforced

### Linting (Android Lint)

- `./gradlew lint`: runs Android Lint on the default variant
- `./gradlew check`: includes lint + unit tests
- Config in `app/lint.xml` (silences non-actionable checks)
- Keep lint clean before committing; no errors allowed

## Git & Commits

- Commit messages follow [Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/): `<type>(<scope>): <description>`, lowercase imperative, no trailing period. See [CONTRIBUTING.md#commit-messages](CONTRIBUTING.md#commit-messages) for types and scopes.
- Stage only intended files with `git add <file>`; read `git status` before every commit; never use `git add -A` or `git commit -a` without verifying the diff.
- Do not add wordy commit messages, a short description is more than enough.

## Architecture

- **Min SDK**: 31, **Target SDK**: 36, **JVM 17**, **Compose + Material3** with dynamic color
- **No Google Play Services**: sideloaded APK only
- **Landscape-locked** fullscreen via `WindowCompat.getInsetsController`
- **Navigation**: flat overlay pattern via `AnimatedVisibility`. No NavHost.
- **Gamepad focus isolation**: background screens removed from composition when overlays active
- **OkHttp 4.12.0** via `HttpClientProvider` singleton (15s / 60s read timeout variants)
- **SAF folder picker** for storage; `DolphinTree` wraps the SAF grant + `DocumentFile` operations
- **Dolphin launch**: `AutoStartFile` intent extra + `Dolphin.ini` `ISOPathN` library registration; `DolphinLauncher.launchRetroRewind()` orchestrates the full pre-launch flow with fallback
- **i18n**: all strings in `res/values/strings.xml`; Compose uses `stringResource()`, VMs use `app.getString()`
- **Icons**: Material Symbols (Rounded) vector drawables in `res/drawable/ic_*.xml`, sourced from [fonts.google.com/icons](https://fonts.google.com/icons) (Android tab, 24dp). Loaded in Compose via `ImageVector.vectorResource(R.drawable.ic_x)`. Auto-mirrored icons (`arrow_back`, `exit_to_app`, `shortcut`) declare `android:autoMirrored="true"` on the root `<vector>` to mirror in RTL locales. The deprecated `androidx.compose.material.icons` artifacts are intentionally not used.

## Package Structure

```
com.skiletro.wheelwitch
├── MainActivity.kt
├── model/         (data types: SemVersion, PackStatus, UpdateEntry, DeletionEntry, SaveFileInfo, LicenseInfo, ServerInfo, etc.)
├── data/          (storage: DolphinPaths, DolphinTree, DolphinConfig, SaveManager, RksysParser, GameTypeParser)
├── network/       (HTTP + JSON parsers: VersionFileParser, RoomStatusParser, RaceStatsParser, ServerHealthParser, LeaderboardParser, TimeTrialParser)
├── domain/        (business logic: RewindPackManager)
├── util/{io,net,mii,launcher,log,json,prefs}/   (utilities grouped by concern)
├── ui/{components,screens,theme}/
└── viewmodel/
    ├── PackUpdateViewModel   : install/update state machine + SAF tree wiring
    ├── SaveDataViewModel     : per-region parse, leaderboard merge, backup/restore/delete, multi-region
    ├── MiiMakerViewModel     : WAD install (Mutex-guarded), launch, delete
    ├── OnlineViewModel       : rooms, leaderboard (Channel-based race-free), health, race stats
    └── UiState               : sealed class for pack update flow
```

The sub-packages under `util/` are intentional. Keep new files in the right sub-package:
- `io/`: `FileDownloader`, `ByteReader`, `OptionalFileTree`
- `net/`: `HttpClientProvider`, `NetworkExtensions`
- `mii/`: `MiiWadInstaller`, `MiiFaceCache`, `MiiEndpoints`
- `launcher/`: `DolphinLauncher`, `BugReportLauncher`
- `log/`: `LogBuffer`, `LogEntry`, `LogExporter`, `MemoryBufferTree`, `AppReleaseLogTree`
- `json/`: `JsonExtensions`
- `prefs/`: `Prefs`, `PrefsKeys`

## Key Decisions

- **Screen nav**: `MainScreen` orchestrates `HomeScreen`/`SettingsScreen`/`OnboardingScreen`; `HomeScreen` orchestrates `OnlineMenuScreen`/`SaveInfoScreen`; `OnlineMenuScreen` uses `AnimatedContent` with `OnlineMenuPage` enum
- **Dolphin tree**: `DolphinTree` (SAF wrapper) is the single source of truth for the user-picked folder; `DolphinPaths` derives physical paths via the package-swap trick; `DolphinConfig` is the pure INI parser for `Dolphin.ini` `ISOPathN` registration
- **Path consistency invariant**: every path inside `rr_autostartfile.json` and the `AutoStartFile` extra must derive from the same `DolphinPaths.physicalRoot(context)` call. Riivolution's native code can't resolve `content://` URIs.
- **Launch flow**: `DolphinLauncher.launchRetroRewind(context)` does (1) validate Dolphin, (2) load tree, (3) pick ROM, (4) upsert `Dolphin.ini`, (5) write descriptor, (6) fire intent; falls back to bare-Dolphin launch if any step throws
- **PrimaryActionButton**: 56dp, filled primary, `titleSmall` semi-bold; **SecondaryActionButton**: 48dp, outlined
- **Leaderboard**: clickable rows with 5dp primary border on focus; one-shot `hasRequestedFocus` guard for pagination
- **File downloads**: `FileDownloader.downloadToFile()` with progress callback, HTTP validation, configurable client
- **Mii Maker WAD**: downloads zip from GameBanana, extracts `.wad`, launches via `ACTION_VIEW` + FileProvider
- **Update server**: see `VersionFileParser.RR_BASE`; version/deletion manifest files; full reinstall when no local version exists
- **Copy buffer**: 262144 bytes; **Parallel incremental downloads** via `async/await`
- **Multi-region saves**: `SaveManager.Region` enum (PAL/USA/JPN) mapped from the ROM filename prefix; one save file per region

## Key Constants

All values live in code. This table maps each thing to its canonical source.

| Thing | Source |
|-------|--------|
| Dolphin package | `DolphinLauncher.DOLPHIN_PACKAGE` |
| Dolphin activity | `DolphinLauncher.DOLPHIN_MAIN_ACTIVITY` |
| Launch intent extra | `DolphinLauncher.LAUNCH_EXTRA_NAME` |
| Launch descriptor filename | `DolphinLauncher.RR_JSON_NAME` |
| Display name | `DolphinLauncher.DISPLAY_NAME` |
| ROM extensions | `DolphinLauncher.ROM_EXTENSIONS` |
| Riivolution XML default | `DolphinLauncher.DEFAULT_XML_REL_PATH` |
| Update server host | `VersionFileParser.RR_BASE` |
| API host | `VersionFileParser.RWFC_API` |
| Badges host | `VersionFileParser.BADGES_BASE` |
| GitHub releases page | `GitHubReleaseParser.RELEASES_PAGE_URL` |
| WheelWitch subpath | `DolphinPaths.WHEELWITCH_SUBPATH` |
| Config INI filename | `DolphinTree.CONFIG_INI_NAME` |
| Version filename | `DolphinTree.VERSION_FILE_NAME` |
| Metadata XML filename | `DolphinTree.METADATA_XML_NAME` |

## Tests

### Stack
JUnit 5, MockK 1.13.x, Truth 1.4.x, `org.json:json` test dep (Android stubs throw "not mocked")

### Running
```bash
./gradlew testDebugUnitTest                                         # all unit tests
./gradlew testDebugUnitTest --tests "com.skiletro.wheelwitch.model.SemVersionTest"  # single class
```

### Test files

| File | Tests | What it covers |
|---|---|---|
| `model/SemVersionTest.kt` | 6 | `parse()` valid/invalid, `compareTo()`, `toString()` |
| `network/RoomStatusParserTest.kt` | 7 | `parseRooms()`: full room, null race, edge cases |
| `util/launcher/DolphinLauncherTest.kt` | 31 | `buildLaunchJson` field shape, `launch` intent, `registerRomPathInConfig`, `pickRomFile`, `startDolphin`, `launchRetroRewind` (auto-start + fallback + NoRom + StorageNotConfigured + DolphinNotInstalled) |
| `util/mii/MiiFaceCacheTest.kt` | 5 | cache size, clear, init |
| `util/mii/MiiWadInstallerTest.kt` | 8 | WAD validation, zip extraction, HACS variant |
| `util/log/LogBufferTest.kt` | 6 | capacity, wrap-around, clear, snapshot ordering |
| `util/log/MemoryBufferTreeTest.kt` | 4 | fan-out to buffer, minPriority filtering |
| `util/log/AppReleaseLogTreeTest.kt` | 2 | drops INFO/DEBUG/VERBOSE, keeps WARN/ERROR/ASSERT |
| `util/log/LogTextRendererTest.kt` | 8 | line parsing (level letters, plain lines, messageStart), colourised report preserves text + per-level span colours |
| `util/io/OptionalFileTreeTest.kt` | 9 | enable/disable, file append, 1MB rotation |
| `util/io/FileDownloaderTest.kt` | 6 | MockWebServer HTTP flows, backoff, 4xx/5xx discrimination |
| `util/io/ByteReaderTest.kt` | 12 | byte-level reads, endianness, bounds |
| `util/FormatTest.kt` | 6 | byte/percentage formatting |
| `data/RksysParserTest.kt` | 9 | RKPD binary parsing: slots, friend code golden |
| `data/GameTypeParserTest.kt` | 23 | ISO/RVZ/WBFS/WAD detection, game-id extraction |
| `data/DolphinPathsTest.kt` | 14 | `physicalRoot` package-swap (release + debug), path helpers |
| `data/DolphinTreeTest.kt` | 35 | lazy subdirs, `validate`, `fromPersisted`/`persist`, `copyRomFromSource`, `extractZipToPack`, `writeLaunchJson`/`readLaunchJson`, `readVersion`/`writeVersion`, `readConfigIni`/`writeConfigIni`, persist/release URI permission |
| `data/DolphinConfigTest.kt` | 25 | `IsoPaths.toIniLines`, `read`/`upsert`/`remove`, idempotency, comment preservation, `dolphinUserTreeUri` |
| `data/SaveManagerTest.kt` | 9 | region mapping, `listRegions`, `hasSave`/`backup`/`restore`/`delete` |
| `network/VersionFileParserTest.kt` | 17 | update/deletion parsing, leaderboard, health, tracks, race stats |
| `network/GitHubReleaseParserTest.kt` | 6 | `parseLatestReleaseVersion()`: release title extraction, no-version/null/empty/malformed payloads |
| `domain/RewindPackManagerTest.kt` | 11 | `checkStatus`, `installLatest` (zip + extract + version-after-extract, server failures, extract-failure no-version-write), `update` (incremental steps) |
| `viewmodel/PackUpdateViewModelTest.kt` | 10 | init/checkStatus/install/update/clearError state machine |
| `viewmodel/AppUpdateViewModelTest.kt` | 7 | init latest-vs-current resolution, fetch failure, disabled skip, unparseable current version, `dismissDialog` |
| `viewmodel/SaveDataViewModelTest.kt` | 12 | refresh, region selection, slot selection, leaderboard merge, backup/restore/delete delegation |
| `viewmodel/LogViewerViewModelTest.kt` | 4 | init load, loading state, reload, copy bumps trigger |

### Testability notes
- Pure functions tested directly (no Android deps): SemVersion, parseRooms(), parseUpdatesText(), `DolphinConfig`, `DolphinPaths.physicalRoot`, `DolphinLauncher.buildLaunchJson`, etc.
- `RksysParser` and `MiiFaceCache` use `java.util.Base64` for JVM testability
- `DolphinTree` is tested with MockK-stubs for `ContentResolver`/`DocumentFile`; constructor is cheap and side-effect free (lazy subdirs).
- ViewModels test through MockK constructor injection. `PackUpdateViewModel` takes a `managerFactory` lambda; `SaveDataViewModel` takes `treeFactory`, `parser`, `leaderboardFetcher`, `ioDispatcher`
- Static `object` singletons mocked via `mockkObject()`; `RewindPackManager` is a class with constructor injection
- Android-dependent orchestration (intent firing, SAF I/O) is mocked via the two-arg `DolphinLauncher.launchRetroRewind(context, tree)` overload. The single-arg version delegates to `DolphinTree.fromPersisted` which the production home-screen caller invokes anyway
