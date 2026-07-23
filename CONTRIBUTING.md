# Contributing to Wheel Witch

## Prerequisites

- **JDK 17+** (required by Gradle 9.4.1)
- Android SDK (managed automatically via Gradle)
- (Optional) Android Studio for the emulator and layout previews

### Nix flake

If you have [Nix](https://nixos.org) (with flakes enabled) and [direnv](https://direnv.net) + [nix-direnv](https://github.com/nix-community/nix-direnv) installed, the `.envrc` will auto-load a Nix shell containing JDK 21, the Android SDK, and `adb`. The first entry into the directory builds the shell (a few minutes for the Android SDK).

> **aarch64 Linux users**: Android build tools (aapt2) are x86_64-only. Add
> `boot.binfmt.emulatedSystems = [ "x86_64-linux" ];` to your NixOS
> configuration and run `nixos-rebuild switch` to enable transparent QEMU
> emulation. On non-NixOS, install `qemu-user-static` via your package manager.

Without direnv, enter the shell manually:

```bash
nix develop
```

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

Boot an Android emulator (one-time AVD creation required):

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

`./gradlew assembleDebug testDebugUnitTest` must stay green. Pull requests run tests automatically in CI.

No formal CLA; if you contribute code, please add yourself to a credits section if we add one.

## Build

```bash
./gradlew assembleDebug                  # build APK
./gradlew assembleRelease                # release build (R8/ProGuard)
./gradlew testDebugUnitTest              # run unit tests
```

The debug APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

For a **signed release APK**, run `scripts/setup-signing.sh` to generate a keystore
and `.env` file, then:

```bash
just build-release     # Nix shell — loads .env automatically
# or
source .env && ./gradlew assembleRelease  # without Nix
```

The keystore is resolved relative to the project root. Defaults: PKCS12, RSA-4096,
SHA512withRSA, 10000-day validity.

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
