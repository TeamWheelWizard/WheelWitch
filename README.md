<div align="center">
  <img src=".github/assets/logo/wizard-hat.svg" alt="Wheel Witch" width="120">
</div>

<h1 align="center">Wheel Witch</h1>

<br>

<div align="center">
  <a href="https://github.com/skiletro/WheelWitch/actions/workflows/build.yml"><img src="https://github.com/skiletro/WheelWitch/actions/workflows/build.yml/badge.svg" alt="Build"></a>&nbsp;
  <img src="https://img.shields.io/badge/license-GPL--3.0-blue.svg" alt="License: GPL-3.0">&nbsp;
  <img src="https://img.shields.io/badge/min%20SDK-31-green.svg" alt="Min SDK 31">&nbsp;
  <img src="https://img.shields.io/badge/Kotlin-1.9-purple.svg" alt="Kotlin">&nbsp;
  <img src="https://img.shields.io/badge/Jetpack%20Compose-2026.02-blue.svg" alt="Compose">
</div>

<br>

<div align="center">
  <a href="https://github.com/skiletro/WheelWitch/releases/latest"><img src="./.github/assets/badges/github.webp" alt="Latest Release" width="220"></a>&nbsp;&nbsp;
  <a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22com.skiletro.wheelwitch%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2Fskiletro%2FWheelWitch%22%2C%22author%22%3A%22skiletro%22%2C%22name%22%3A%22WheelWitch%22%2C%22additionalSettings%22%3A%22%7B%5C%22includePrereleases%5C%22%3Afalse%2C%5C%22apkFilterRegEx%5C%22%3A%5C%22wheelwitch-.%2A%5C%5C%5C%5C.apk%5C%22%2C%5C%22versionExtractionRegEx%5C%22%3A%5C%22%28%5C%5C%5C%5Cd%2B%5C%5C%5C%5C.%5C%5C%5C%5Cd%2B%5C%5C%5C%5C.%5C%5C%5C%5Cd%2B%29%5C%22%2C%5C%22releaseTitleAsVersion%5C%22%3Atrue%7D%22%7D"><img src="./.github/assets/badges/obtainium.webp" alt="Add to Obtainium" width="220"></a>
</div>

## Screenshots

| Home | Online Menu | Online Rooms |
|------|-------------|--------------|
| ![Home](.github/assets/screenshots/home.webp) | ![Online Menu](.github/assets/screenshots/online_menu.webp) | ![Online Rooms](.github/assets/screenshots/online_rooms.webp) |

| Quick Launch | Licenses | Settings |
|--------------|----------|----------|
| ![Quick Launch](.github/assets/screenshots/quick_launch.webp) | ![Licenses](.github/assets/screenshots/licenses.webp) | ![Settings](.github/assets/screenshots/settings.webp) |

| Race Stats | | |
|------------|---|---|
| ![Race Stats](.github/assets/screenshots/race_stats.webp) | ![](.github/assets/filler.webp) | ![](.github/assets/filler.webp) |

## About

Wheel Witch is an Android companion app for [Retro Rewind](https://wiki.tockdom.com/wiki/Retro_Rewind), a custom Mario Kart Wii distribution.
It downloads and incrementally updates the pack from the RWFC server, then launches Dolphin with the pack pre-loaded.

## Download

The latest signed release APK is built automatically when a version tag is pushed and published as a [stable release](https://github.com/skiletro/WheelWitch/releases/latest) with an auto-generated changelog. You can also install it via [Obtainium](https://github.com/ImranR98/Obtainium) by importing the config from the button above.

To build from source or contribute, see [CONTRIBUTING.md](CONTRIBUTING.md#build).

## Verifying your install

You can verify the APK signature with [AppVerifier](https://github.com/soupslurpr/AppVerifier).
The signing certificate is embedded in the APK and matches the values below, so an
unmodified build will always verify the same way across releases.

- **SHA-256 fingerprint:** `75:96:8A:48:2B:75:E9:F1:72:36:3E:7D:37:5F:82:C3:BA:47:9A:84:6D:26:79:AC:C1:B7:9C:2F:11:63:70:00`
- **SHA-1 fingerprint:** `68:53:04:D6:F2:36:5F:34:98:E2:FA:DE:6A:67:7B:80:B8:70:4A:C3`

The V4 signature is also distributed as `wheelwitch-<version>-<hash>.apk.idsig`
alongside the APK in the [stable release](https://github.com/skiletro/WheelWitch/releases/latest)
for offline verification.

## Requirements

- Android 12+ (API 31)
- [Dolphin Emulator](https://dolphin-emu.org) installed
- Mario Kart Wii ISO

## First Time Setup

1. Open the app and tap **Let's get started**.
2. Install Dolphin Emulator if prompted, then tap **Check Again**.
3. Tap **Grant Access** and pick Dolphin's `org.dolphinemu.dolphinemu` folder.
4. Tap **Select ROM File** and pick your Mario Kart Wii ISO.
5. Tap **Continue** to enter the home screen.

For returning users, the gear icon opens Settings.

## Building & Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for build instructions, signing setup, and contribution guidelines.

## TODO
Please reference <a href="https://github.com/skiletro/WheelWitch/issues?q=sort%3Aupdated-desc%20is%3Aissue%20state%3Aopen%20label%3Atodo">the issues page</a>.

## Credits

- **[Retro Rewind](https://rwfc.net)** and the **[Wheel Wizard](https://github.com/TeamWheelWizard/WheelWizard)** team for the pack format and update server
- **[Dolphin Emulator](https://dolphin-emu.org)**
- **[Tockdom wiki](https://wiki.tockdom.com)**
- **[Chadderz](https://chadsoft.co.uk/contact.html)** for her [Terrible Mario Kart Font](https://wiki.tockdom.com/wiki/CTMKF)
- **[Jetpack Compose](https://developer.android.com/jetpack/compose)**, **[Material 3](https://m3.material.io)**, and **[OkHttp](https://square.github.io/okhttp/)** for the building blocks
- **[Obtainium](https://github.com/ImranR98/Obtainium)** for making sideloaded auto-updates painless
- **[Composables](https://composables.com/)** for the cool icons

This project is unaffiliated with Nintendo, Retro Rewind, or Wheel Wizard.
Rights to Mario Kart Wii go to Nintendo, Retro Rewind to the Retro Rewind Team, and Wheel Wizard to the Wheel Wizard team.

> [!IMPORTANT]
> Parts of this codebase were written with assistance from [MiniMax M3](https://minimax.io) and [GLM-4.7](https://z.ai) as a way to get more familiar with AI tooling.
> It was especially useful for writing test cases and porting save-related logic from the original Wheel Wizard project.
> I'm always open to conversation about AI in development and I believe it would have been disingenuous to omit the fact that it was used; I see these tools as a way to help write code, not a replacement for understanding what you're building.
> They should be used with care and in moderation.
>
> Small disclaimer over. Please don't hate me. :(
