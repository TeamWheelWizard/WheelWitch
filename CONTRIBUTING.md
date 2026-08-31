# Contributing to Wheel Witch

## Prerequisites

- **JDK 17+** (required by Gradle 9.4.1)
- Android SDK (managed automatically via Gradle)
- (Optional) Android Studio for the emulator and layout previews

### devenv

If you have [devenv](https://devenv.sh) (2.2+) installed, `devenv shell` drops you
into an environment containing JDK 21, the Android SDK, and `adb`. The first entry
into the shell builds the Android SDK (a few minutes).

> **x86_64 Linux only**: Android build tools (aapt2) are x86_64-only, and the
> shell refuses to evaluate on any other system.

Signing secrets are managed with [secretspec](https://secretspec.dev) and loaded
into the shell automatically; see [Build](#build).

Common tasks (after entering the shell):

```bash
just build            # assemble debug APK
just test             # run unit tests
just format           # spotless + ktfmt
just lint             # android lint
just clean            # gradle clean
just check            # build + test
```

Build, install and launch on a connected adb device:

```bash
just install
```

Boot an Android emulator (one-time AVD creation required; the emulator is not in
the shell by default — set `android.emulator.enable = true` in `devenv.nix` first):

```bash
echo "no" | avdmanager create avd --force --name wheelwitch \
  --package 'system-images;android-36;google_apis;x86_64' --device pixel
emulator @wheelwitch
```

## Contributing

1. Fork this repository
2. Create a feature branch (`git checkout -b fix-the-thing`)
3. Commit your changes and open a pull request

### Commit messages

Use [Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/):

```
<type>(<scope>): <description>
```

Types: `feat:` (new user-facing), `fix:` (bug fix), `refactor:` (neither), `perf:`, `test:`, `docs:`, `chore:`, `build:`.

Scopes match the package layout. For example: `dolphin`, `pack`, `save`, `mii-maker`, `online`, `leaderboard`, `rooms`, `race-stats`, `onboarding`, `home`, `settings`, `quick-launch`, `theme`, `gamepad`, `ui`, `storage`, `i18n`, `viewmodel`.

Description is lowercase imperative, no trailing period.

### Build gates

`./gradlew assembleDebug testDebugUnitTest` must stay green. Pull requests run unit tests automatically in CI; `dev` pushes also build a signed CI artefact without publishing a GitHub release.

No formal CLA; if you contribute code, please add yourself to a credits section if we add one.

## Build

```bash
./gradlew assembleDebug                  # build APK
./gradlew assembleRelease                # release build (R8/ProGuard)
./gradlew testDebugUnitTest              # run unit tests
```

The debug APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

For a **signed release APK**, run `just setup-signing` to generate a keystore and
store the signing secrets with [secretspec](https://secretspec.dev) (dotenv
provider, written to the gitignored `.env`), then:

```bash
just build-release     # from the devenv shell — signing secrets load automatically
# or
secretspec run -- ./gradlew assembleRelease  # without devenv
```

The keystore is resolved relative to the project root. Defaults: PKCS12, RSA-4096,
SHA512withRSA, 10000-day validity.

### Stable releases

Stable releases are tag-driven. Push a tag in the form `vM.m.p`, such as `v1.0.0`;
major versions must be at most 2099, and minor and patch versions at most 999.
The release workflow validates the tag, runs the unit tests, builds and verifies the
signed APK, and publishes the APK and `.idsig` file as a non-prerelease GitHub release.
Tags with a non-semantic version are rejected before publication.

## Project structure

```
com.skiletro.wheelwitch
├── model/         (data types: SemVersion, PackStatus, SaveFileInfo, etc.)
├── data/          (storage: DolphinPaths, DolphinTree, DolphinConfig, SaveManager, RksysParser, GameTypeParser)
├── network/       (HTTP + JSON parsers: VersionFileParser, RoomStatusParser, etc.)
├── domain/        (business logic: RewindPackManager)
├── util/{io,net,mii,launcher,log,json,prefs}/  (utilities grouped by concern)
├── ui/{components,screens,theme}/
└── viewmodel/     (Android ViewModels per screen)
```

Strings go in `res/values/strings.xml`; Compose screens use `stringResource()`.
