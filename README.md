<p align="center">
  <img src="logo.png" alt="WinLite" width="600">
</p>

# WinLite — Winlator Bionic(ludashi) fork

Android app for running Windows (x86\_64) games and apps via Wine. Supports `x86_64` containers (Box86/Box64) and `Arm64EC` containers (FEXCore, optional WowBox64).

Fork of [Winlator-Ludashi](https://github.com/StevenMXZ/Winlator-Ludashi) by StevenMXZ, itself based on [Winlator Bionic](https://github.com/Pipetto-crypto/winlator). Focus: built-in frame generation (**WFG**), a Vulkan renderer with more upscalers, a fully rewritten interface, lighter containers and safer game saves.

> **What is inherited from Ludashi 4.1:** the ReShade-style presets, post effects (DLS / CRT / HDR / Natural), SGSR / FSR / Lanczos 2 / Color Boost upscalers, stretch modes, present-mode and surface-format selection, the HUD, the in-game task manager, SteamGridDB / TheGamesDB covers, gamepad modes, magnifier, haptics and the Turnip driver set. They are all still here, but they are **not** new in WinLite. The sections below mark what WinLite adds or changes.

## Why this fork

| Feature | What you get |
|---|---|
| 🎞️ **Frame generation (WFG)** | Optical-flow frame interpolation built into the Vulkan renderer. Toggle it in the in-game sidebar, pick a quality and a target FPS. No external layers, no extra files to import. |
| 🔍 **More upscalers** | Adds SGSR HQ (edge-directed) and NVIDIA Image Scaling (NIS). |
| 🎨 **Compose UI** | Whole app rewritten in Jetpack Compose (no Fragments left), plus a new in-game sidebar. |
| 🔊 **DirectAudio** | Wine → AAudio driver for low-latency sound (Proton 9 / 11 Arm64EC). |
| 🧩 **Vulkan wrapper** | *Wrapper WL* (Mesa 26 based) plus GPU identity spoofing. |
| 🗂️ **Save profiles** | Saves live in `Winlator/saves` and survive any container change. |
| 🕹️ **Dynamic Stick** | On-screen stick that appears under your finger and follows it. |
| 📦 **Component catalog** | One place for Proton, drivers and runtimes, with editable download sources. |

## Features

### 🖥️ Vulkan renderer
Vulkan is the only renderer in WinLite (the OpenGL/EGL one from Ludashi is removed).
- **Present mode** per container: `fifo` (default) or `mailbox`.
- **Surface format:** RGBA (default) or BGRA, with an optional UBWC compression toggle (Adreno, RGBA only).
- **Upscalers and effects:** SGSR, FSR, Lanczos 2, Color Boost, post effects (DLS / CRT / HDR / Natural) and ReShade-style presets come from Ludashi. WinLite adds **SGSR HQ** and **NIS**.
- **FPS limiter** in the sidebar: Off, 5–120 FPS in 5 FPS steps, or a custom value. It works for games that present through the Present extension as well as for MIT-SHM blit clients.
- **Frame generation** is part of the renderer's own compositor (see below).

### 🎞️ Frame generation (WFG)
WFG is a Vulkan compute implementation of optical-flow frame interpolation. It runs inside the renderer, so it needs no Vulkan layer, no environment variables and no files imported from elsewhere.

- **How it works:** the scene is drawn into an intermediate image. For every new game frame, WFG estimates motion between the previous and the current frame and renders the in-between frames straight into extra swapchain images. They are presented first, followed by the real frame.
- **Motion estimation:** Dense Inverse Search (DIS) optical flow, combined with Farneback polynomial-expansion flow on the coarse levels (the default hybrid).
- **Motion Quality:** *Fast* or *Balanced* (how many pixels of the frame's shorter side are analysed).
- **Target FPS:** *Max refresh*, 60, 90, 120 or 144. The target never exceeds the display's refresh rate.
- **Automatic multiplier:** the number of generated frames per game frame (up to 7) follows the ratio between your target and the game's real frame rate. No generation happens when the game already reaches the target (or is within roughly 1.5× of it).
- **Low frame rates:** generation switches itself off below about 20 FPS and comes back above about 24 FPS. Below that, motion between two real frames is too large for the search to follow.
- **Letterboxes:** only the game's own area is analysed; the borders around it are filled from the composite.
- **Settings:** switch on the fly from the sidebar (Frame Generation card); the choice is remembered per container or per shortcut.
- **Latency:** because the in-between frames are shown before the real one, the game's frame appears one display frame later than without WFG. FIFO presenting is used for pacing while it is on.

WFG is the work of [qwertypower](https://github.com/qwertypower) (DEVAR Entertainment LLC), released under the MIT license. See [Credits](#credits).

### 🎨 Interface
- Library, game details, containers, shortcuts, settings, file manager and controls editor are all Jetpack Compose. In Ludashi these were mostly XML Views and Fragments; none remain.
- **In-game sidebar** rebuilt in Compose with Rendering, HUD, Screen, Input and Task Manager panels.
- Game covers and banners from SteamGridDB / TheGamesDB, same as Ludashi (the code behind them was refactored).
- Portrait and landscape layouts, themes, HUD (FPS, CPU/GPU load, RAM, power, temperature), in-app **debug log viewer**.

### 🧩 Graphics & audio
- **Vulkan wrapper:** *Wrapper WL* (Mesa 26 based).
- **GPU identity spoofing:** vendor / device ID, device name, driver name / info / version (also feeds WineD3D's PCI IDs).
- Turnip/Adreno drivers (`turnip26.2.0`, `v863`) are bundled as before; the Turnip driver is preselected when the device supports it. OpenGL via Zink or freedreno.
- **Audio drivers:** ALSA, PulseAudio-GN and **DirectAudio** (Proton 9 / 11 Arm64EC): AAudio output, optional microphone, 64- and 32-bit guests. Based on [The412Banner/directaudio](https://github.com/The412Banner/directaudio). The legacy PulseAudio build from Ludashi was dropped; PulseAudio-GN stays.
- **Force SSBS** (Proton 9 / 11 Arm64EC): keeps the CPU's speculative-store-bypass-safe state set across Wine's arm64 signal returns, avoiding a slow mode after exceptions and thread suspends.

### 🗂️ Save profiles & backups
Normally saves die with the container. A save profile keeps them in Android storage and links them into whichever container you launch from.

- **Mode per shortcut:** *Off* (default) · *Own* (`Winlator/saves/<Game>/`) · *Shared* (`Winlator/saves/_Common/`).
- **Covers:** `AppData`, `Documents`, `Saved Games`, `Public Documents` **and registry keys** (Unity PlayerPrefs, older games).
- **Safe:** matched by id (renaming is fine); a re-added game picks its old saves up again; other games in the same container are untouched.
- **Backups** (`Winlator/backups/<Game>/`): manual, automatic on exit (only if saves changed), and a "before restore" snapshot.
- Deleting a shortcut offers to delete its saves too (default: keep).

### 🕹️ Input
New in WinLite:
- **Dynamic Stick** (off by default): touch anywhere in the stick's zone and it appears under your finger; drag past its rim and it follows; release and it returns home. Buttons inside the zone keep priority. Tune *Zone Size* (1.5×–5×) and *Follow Speed* per stick.
- Proper relative mouse and DirectInput on Wine 10+ / Proton 11 (XInput2 in the built-in X server).
- Smooth, refresh-rate-independent touch pointer acceleration.
- **Tap to Click** in the sidebar.

Inherited from Ludashi: gamepad mode per container/shortcut (XInput, DInput, both or none), haptics, magnifier, Relative Mouse and Cursor Speed.

### 📦 Components & sources
- One catalog for Proton/Wine, GPU drivers, FEXCore/Box64, DXVK/VKD3D and more, with verified downloads.
- **Winlator Services:** replace any download address (components, drivers, controls, artwork) with your own mirror.
- GitHub "channels" for app updates and component catalogs.

## Compared to Ludashi 4.1

| | Ludashi 4.1 | WinLite |
|---|---|---|
| UI | XML Views + Fragments, partly Compose | Jetpack Compose only |
| Frame generation | — | WFG (optical flow), built into the Vulkan renderer |
| Upscalers | SGSR, FSR, Lanczos 2, Color Boost | Same, plus SGSR HQ and NIS |
| Audio | ALSA, PulseAudio, PulseAudio-GN | ALSA, PulseAudio-GN, DirectAudio |
| Vulkan wrapper | 1 | 1 (Wrapper WL, Mesa 26 based) + GPU identity spoofing |
| Saves | In container | Save profiles + backups |
| On-screen stick | Fixed | Optional Dynamic Stick |
| Touch / mouse | Basic | Pointer acceleration, Tap to Click, XInput2 |
| Download sources | Hardcoded | Editable in-app |
| Artwork | SteamGridDB / TheGamesDB | Same (refactored) |
| Debug tools | — | In-app debug log viewer |

Removed to stay lean: OpenGL (EGL) renderer, the legacy PulseAudio build, OpenXR, Big Picture mode and the bundled `patchelf`.

## Installation
1. Download the latest APK from [Releases](../../releases).
2. Install, launch and wait for first-time setup.
3. Create a container, choose Wine/Proton, add your game.

Package: `com.winlite.bionic` · Arm64 only · Android 9+ (`minSdk 28`)

## Tips
- Frame generation: open the sidebar → Rendering → Frame Generation and switch on **WFG Frame Generator**. It works best when the game holds a steady 30–60 FPS; set a target FPS that is clearly higher than that (for example 30–45 FPS → 90 or 120).
- Pair WFG with the FPS limiter: a steady, capped game frame rate gives more evenly spaced generated frames than a fluctuating one.
- If frame generation does nothing, check the game's real frame rate: it stays off below roughly 20 FPS and when the game already reaches the target.
- DirectAudio and Force SSBS need a Proton 9 or 11 Arm64EC container.
- Slow `x86_64` container: set the Box86/Box64 preset to **Performance** (Container → Advanced); use **Intermediate** for installers.
- `Arm64EC` container: try other FEXCore versions.
- .NET apps: install Wine Mono (Start Menu → System Tools).
- Old games not starting: add `MESA_EXTENSION_MAX_YEAR=2003` to environment variables.
- Launch games from their shortcuts — each has its own settings.

## Building
JDK 17, Android SDK (compileSdk 35), NDK, `glslang-tools`, `ninja`, `zstd`. See `.github/workflows/build.yml` for exact steps (it also fetches `libadrenotools` and `liblinkernsbypass` at pinned commits). The WFG compute shaders are compiled to SPIR-V during the native build (`glslc` or `glslangValidator`).

```bash
chmod +x gradlew
./gradlew assembleRelease
```

## Credits
[Winlator](https://github.com/brunodev85/winlator) (brunodev85) · [Winlator Bionic](https://github.com/Pipetto-crypto/winlator) (Pipetto-crypto) · [Winlator-Ludashi](https://github.com/StevenMXZ/Winlator-Ludashi) (StevenMXZ) · [coffincolors fork](https://github.com/coffincolors/winlator) · [DirectAudio](https://github.com/The412Banner/directaudio) (The412Banner) · SGSR by Qualcomm · NIS by NVIDIA · force-SSBS approach by Sonicadvance1 (FEX-Emu) and Wine MR 12262

**Frame generation:** WFG by [qwertypower](https://github.com/qwertypower) (DEVAR Entertainment LLC), the original author of the frame generator, MIT license. It builds on Dense Inverse Search optical flow (Till Kroeger et al., OF\_DIS; OpenCV `DISOpticalFlow`) and Farneback's polynomial-expansion optical flow (OpenCV, Apache-2.0). Full attribution is in `app/src/main/cpp/wfg/LICENSE`.

[Wine](https://www.winehq.org/) · [Box86/Box64](https://github.com/ptitSeb) · [FEX-Emu](https://github.com/FEX-Emu/FEX) · [PRoot](https://proot-me.github.io) · [Mesa](https://www.mesa3d.org) · [DXVK](https://github.com/doitsujin/dxvk) · [VKD3D](https://gitlab.winehq.org/wine/vkd3d) · [D8VK](https://github.com/AlpyneDreams/d8vk) · [CNC DDraw](https://github.com/FunkyFr3sh/cnc-ddraw)

## License
See [LICENSE](LICENSE). The frame-generation code in `app/src/main/cpp/wfg/` is by qwertypower and carries its own MIT license (`app/src/main/cpp/wfg/LICENSE`).
