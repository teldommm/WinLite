<p align="center">
  <img src="logo.png" alt="WinLite" width="600">
</p>

# WinLite — Winlator Bionic(ludashi) fork

Android app for running Windows (x86\_64) games and apps via Wine. Supports `x86_64` containers (Box86/Box64) and `Arm64EC` containers (FEXCore, optional WowBox64).

Fork of [Winlator-Ludashi](https://github.com/StevenMXZ/Winlator-Ludashi) by StevenMXZ, itself based on [Winlator Bionic](https://github.com/Pipetto-crypto/winlator). Focus: built-in frame generation, a much more capable Vulkan renderer (frame pacing, more upscalers), a fully rewritten interface, lighter containers and safer game saves.

> **What is inherited from Ludashi 4.1:** the ReShade-style presets, post effects (DLS / CRT / HDR / Natural), SGSR / FSR / Lanczos 2 / Color Boost upscalers, stretch modes, present-mode and surface-format selection, the HUD, the in-game task manager, SteamGridDB / TheGamesDB covers, gamepad modes, magnifier, haptics and the Turnip driver set. They are all still here, but they are **not** new in WinLite. The sections below mark what WinLite adds or changes.

## Why this fork

| Feature | What you get |
|---|---|
| 🎞️ **Frame generation (LSFG)** | Built into the Vulkan renderer: 2x / 3x / 4x or Adaptive FPS. No external layers. |
| ⏱️ **Renderer-side frame pacing** | FPS limiter and presents aligned to the display's vsync grid, minimal input latency, no cursor-induced frame spam. |
| 🔍 **More upscalers** | Adds SGSR HQ (edge-directed) and NVIDIA Image Scaling (NIS). |
| 🎨 **Compose UI** | Whole app rewritten in Jetpack Compose (no Fragments left), plus a new in-game sidebar. |
| 🔊 **DirectAudio** | Wine → AAudio driver for low-latency sound (Proton 9 / 11 Arm64EC). |
| 🧩 **2 Vulkan wrappers** | *Wrapper WL* (Mesa 26 based, default) and *Wrapper Legacy*, plus GPU identity spoofing. |
| 💾 **Thin containers** | Much smaller containers via a shared base prefix and shared components. |
| 🗂️ **Save profiles** | Saves live in `Winlator/saves` and survive any container change. |
| 🕹️ **Dynamic Stick** | On-screen stick that appears under your finger and follows it. |
| 📦 **Component catalog** | One place for Proton, drivers and runtimes, with editable download sources. |

## Features

### 🖥️ Vulkan renderer
Vulkan is the only renderer in WinLite (the OpenGL/EGL one from Ludashi is removed). Changes on top of Ludashi:
- **Smoother FPS limiter:** frames are paced to the display's refresh, so a limit like 45 FPS on 120 Hz stays even instead of stuttering. The game also gets its next buffer as late as possible, which lowers input latency.
- **Less wasted GPU work:** moving the mouse no longer triggers a redraw on every event.
- **Safer buffer handling:** each game buffer is imported once and freed as soon as the GPU is done with it, without stalling the whole device.
- **Frame generation** is built into the renderer (see below).
- **New upscalers:** SGSR HQ and NIS. The rest (SGSR, FSR, Lanczos 2, Color Boost, post effects, ReShade presets, present mode, surface format) come from Ludashi.

### 🎞️ Frame generation (LSFG Native)
- Modes: **2x, 3x, 4x** or **Adaptive FPS** (output paced to a target FPS you set).
- Runs inside the renderer's own compositor — no Vulkan layer, no environment tricks. Any leftover `LSFG_*` variables or the external layer are stripped at launch.
- The interpolated frames are presented *before* the real frame, the cursor is drawn on top of every presented frame (so it is never warped), and presents are spaced over the source-frame interval using display timing when the driver supports it.
- Adjustable flow scale; switch on the fly from the sidebar; set per container or per shortcut.
- **Requires your own `Lossless.dll`** — it isn't bundled. Import it once in Settings; its shaders are read from the file and translated on the device.

### 🎨 Interface
- Library, game details, containers, shortcuts, settings, file manager and controls editor are all Jetpack Compose. In Ludashi these were mostly XML Views and Fragments; none remain.
- **In-game sidebar** rebuilt in Compose with Rendering, HUD, Screen, Input and Task Manager panels.
- Game covers and banners from SteamGridDB / TheGamesDB, same as Ludashi (the code behind them was refactored).
- Portrait and landscape layouts, themes, HUD (FPS, CPU/GPU load, RAM, power, temperature), in-app **debug log viewer**.

### 🧩 Graphics & audio
- **Vulkan wrappers:** *Wrapper WL* (default, Mesa 26 based) and *Wrapper Legacy*. Ludashi ships a single wrapper.
- **GPU identity spoofing** for both wrappers: vendor / device ID, device name, driver name / info / version (also feeds WineD3D's PCI IDs).
- Turnip/Adreno drivers (`turnip26.2.0`, `v863`) are bundled as before; the Turnip driver is preselected when the device supports it. OpenGL via Zink or freedreno.
- **Audio drivers:** ALSA, PulseAudio-GN and **DirectAudio** (Proton 9 / 11 Arm64EC): AAudio output, optional microphone, 64- and 32-bit guests. Based on [The412Banner/directaudio](https://github.com/The412Banner/directaudio). The legacy PulseAudio build from Ludashi was dropped; PulseAudio-GN stays.
- **Force SSBS** (Proton 9 / 11 Arm64EC): keeps the CPU's speculative-store-bypass-safe state set across Wine's arm64 signal returns, avoiding a slow mode after exceptions and thread suspends.

### 💾 Thin containers
- Each container stores only its own changes on top of one shared **base prefix** per Wine/Proton version (copy-on-write overlay via an `LD_PRELOAD` library; Wine is not modified).
- DXVK, VKD3D, FEXCore and other components are stored once and shared (e.g. the 228 MB Direct3D pack exists once, not per container).
- Result: small containers, fast creation.

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
| Audio | ALSA, PulseAudio, PulseAudio-GN | ALSA, PulseAudio-GN, DirectAudio |
| Vulkan wrapper | 1 | 2 (WL / Legacy) + GPU identity spoofing |
| Containers | Full copy each | Thin overlay + shared components |
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
- Frame generation: import `Lossless.dll` in Settings, then choose LSFG in sidebar → Rendering.
- For the smoothest result with the FPS limiter, set it to a value that divides your panel's refresh rate evenly (e.g. 30 / 40 / 60 on 120 Hz); other values still work, with an alternating cadence.
- DirectAudio and Force SSBS need a Proton 9 or 11 Arm64EC container.
- Slow `x86_64` container: set the Box86/Box64 preset to **Performance** (Container → Advanced); use **Intermediate** for installers.
- `Arm64EC` container: try other FEXCore versions.
- .NET apps: install Wine Mono (Start Menu → System Tools).
- Old games not starting: add `MESA_EXTENSION_MAX_YEAR=2003` to environment variables.
- Launch games from their shortcuts — each has its own settings.

## Building
JDK 17, Android SDK (compileSdk 35), NDK, `glslang-tools`, `ninja`, `zstd`. See `.github/workflows/build.yml` for exact steps (it also fetches `libadrenotools` and `liblinkernsbypass` at pinned commits).

```bash
chmod +x gradlew
./gradlew assembleRelease
```

## Credits
[Winlator](https://github.com/brunodev85/winlator) (brunodev85) · [Winlator Bionic](https://github.com/Pipetto-crypto/winlator) (Pipetto-crypto) · [Winlator-Ludashi](https://github.com/StevenMXZ/Winlator-Ludashi) (StevenMXZ) · [coffincolors fork](https://github.com/coffincolors/winlator) · [DirectAudio](https://github.com/The412Banner/directaudio) (The412Banner) · LSFG shader/engine code from the Eden Emulator Project (see `app/src/main/cpp/winlator/renderer/vulkan/lsfg/LICENSE`) · Lossless Scaling by THS · SGSR by Qualcomm · NIS by NVIDIA · force-SSBS approach by Sonicadvance1 (FEX-Emu) and Wine MR 12262

[Wine](https://www.winehq.org/) · [Box86/Box64](https://github.com/ptitSeb) · [FEX-Emu](https://github.com/FEX-Emu/FEX) · [PRoot](https://proot-me.github.io) · [Mesa](https://www.mesa3d.org) · [DXVK](https://github.com/doitsujin/dxvk) · [VKD3D](https://gitlab.winehq.org/wine/vkd3d) · [D8VK](https://github.com/AlpyneDreams/d8vk) · [CNC DDraw](https://github.com/FunkyFr3sh/cnc-ddraw)

## License
See [LICENSE](LICENSE). The frame-generation code in `lsfg/` carries its own license (GPL-3.0-or-later, Eden Emulator Project).
