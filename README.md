<p align="center">
  <img src="logo.png" alt="WinLite" width="600">
</p>

# WinLite — Winlator Bionic (Ludashi) fork

Android app for running Windows (x86\_64) games and apps via Wine. Supports `x86_64` containers (Box86/Box64) and `Arm64EC` containers (FEXCore, optional WowBox64).

Fork of [Winlator-Ludashi](https://github.com/StevenMXZ/Winlator-Ludashi) 5.0 by StevenMXZ, itself based on [Winlator Bionic](https://github.com/Pipetto-crypto/winlator). Focus: a Rust X server, built-in frame generation (**WFG**), a Vulkan renderer with more upscalers, a Compose interface, lighter saves and a reworked Steam client.

## What's different from Ludashi 5.0

| | Ludashi 5.0 | WinLite |
|---|---|---|
| X server | Java + C++ (`ludashix11`) | **Rust** (`xcore`) |
| Frame generation | LSFG | **WFG** (optical flow, built into the renderer) |
| Renderers | Vulkan + OpenGL/EGL | Vulkan only |
| Upscalers | SGSR, FSR, Lanczos 2, Color Boost | Same, plus **SGSR HQ** and **NIS** |
| Audio | ALSA, PulseAudio, PulseAudio-GN | ALSA, PulseAudio-GN, **DirectAudio** |
| Saves | Inside the container | **Save profiles** + backups |
| On-screen stick | Fixed | Optional **Dynamic Stick** |
| Steam | Ludashi Steam | Same base, own client build and restyled UI |
| Download sources | Hardcoded | Editable in-app |

Removed to stay lean: OpenGL (EGL) renderer, LSFG, the legacy PulseAudio build, OpenXR, Big Picture mode, the C++ X server.

## Features

### Rust X server (`xcore`)
The whole X11 server now lives in Rust (`app/src/main/rust/xcore`), linked into `libwinlator.so`.
- **Protocol and extensions:** core X11, Present, DRI3, MIT-SHM, XInput2, RandR, Render, Composite, GLX, Sync.
- **Direct render path:** frames, the window tree, the pointer and the cursor go from the server straight into the Vulkan renderer, bypassing Java and its per-frame JNI calls and thread hops.
- **Guest services:** SysV shared memory and the ALSA server (output through AAudio) run in the same core.
- Java keeps only thin wrappers (`XServer`, `XCore`) for UI and input.

### Vulkan renderer
- Smooth frames.
- Present mode per container: `fifo` (default) or `mailbox`. Surface format RGBA (default) or BGRA, optional UBWC (Adreno, RGBA only).
- Upscalers and effects: SGSR, SGSR HQ, FSR, NIS, Lanczos 2, Color Boost, DLS / CRT / HDR / Natural, ReShade-style presets.
- FPS limiter in the sidebar: 5–120 FPS in 5 FPS steps or a custom value; works with Present and MIT-SHM clients.

### Frame generation (WFG)
Vulkan compute optical-flow interpolation that runs inside the renderer: no layers, env variables or imported files.
- Motion estimation: DIS optical flow with Farneback flow on coarse levels. **Motion Quality:** Fast or Balanced.
- **Frame Multiplier:** x2 / x3 / x4, capped by what the display can show. **Target:** your own FPS (40 up to the panel's refresh), or `0` for the display's refresh rate.
- Turns itself off below ~20 FPS and back on above ~24 FPS. Only the game area is analysed, letterboxes are not.
- Toggle in the sidebar, saved per container or shortcut. Adds one display frame of latency.

### Steam
Built-in Steam client: library sync, QR / credential login with Steam Guard, depot downloads, Steam Cloud, achievements, friends, and optional Goldberg mode. The native client is written in Rust (`winlite-steam-client`). Based on Ludashi 5.0, see [Credits](#credits).

### Interface
- Fully Jetpack Compose (no Fragments left), including a new in-game sidebar (Rendering, HUD, Screen, Input, Task Manager).
- Covers from SteamGridDB / TheGamesDB, HUD (FPS, CPU/GPU load, RAM, power, temperature), in-app debug log viewer.

### Graphics & audio
- **Vulkan wrapper:** *Wrapper WL* (Mesa 26 based) with GPU identity spoofing (vendor / device ID, names, driver info).
- Turnip/Adreno drivers bundled and preselected when supported; OpenGL via Zink or freedreno.
- **DirectAudio** (Proton 9 / 11 Arm64EC): Wine → AAudio, optional microphone, 64- and 32-bit guests.
- **Force SSBS** (Proton 9 / 11 Arm64EC): avoids a slow CPU mode after Wine's arm64 signal returns.

### Save profiles & backups
Saves live in Android storage (`Winlator/saves`) and are linked into whichever container you launch from.
- Mode per shortcut: *Off* · *Own* (`saves/<Game>/`) · *Shared* (`saves/_Common/`).
- Covers `AppData`, `Documents`, `Saved Games`, `Public Documents` and registry keys.
- Backups in `Winlator/backups/<Game>/`: manual, automatic on exit, and a "before restore" snapshot.

### Input
- **Dynamic Stick:** appears under your finger and follows it; tune zone size and follow speed.
- Relative mouse and DirectInput on Wine 10+ / Proton 11 (XInput2), smooth touch pointer acceleration, Tap to Click.
- Gamepad modes, haptics and magnifier are inherited from Ludashi.

### Components
One catalog for Proton/Wine, GPU drivers, FEXCore/Box64, DXVK/VKD3D with verified downloads. **Winlator Services** lets you replace any download address with your own mirror.

## Installation
1. Download the latest APK from [Releases](../../releases).
2. Install, launch and wait for first-time setup.
3. Create a container, choose Wine/Proton, add your game.

Package `com.winlite.bionic` · Arm64 only · Android 9+ (`minSdk 28`)

## Tips
- **WFG:** sidebar → Rendering → Frame Generation. Works best with a steady 30–60 FPS (x2 for 45–60, x3/x4 for 30), or use **Target** with a clearly higher FPS. Pair it with the FPS limiter for even pacing.
- DirectAudio and Force SSBS need a Proton 9 or 11 Arm64EC container.
- Slow `x86_64` container: set the Box86/Box64 preset to **Performance** (Container → Advanced); use **Intermediate** for installers.
- `Arm64EC` container: try other FEXCore versions.
- .NET apps: install Wine Mono (Start Menu → System Tools).
- Old games not starting: add `MESA_EXTENSION_MAX_YEAR=2003` to environment variables.
- Launch games from their shortcuts: each has its own settings.

## Building
JDK 17, Android SDK (compileSdk 35), NDK, Rust with the `aarch64-linux-android` target, `glslang-tools`, `ninja`, `zstd`. Exact steps are in `.github/workflows/build.yml`.

```bash
chmod +x gradlew
./gradlew assembleRelease
```

## Credits
- [Winlator](https://github.com/brunodev85/winlator) (brunodev85) · [Winlator Bionic](https://github.com/Pipetto-crypto/winlator) (Pipetto-crypto) · [coffincolors fork](https://github.com/coffincolors/winlator)
- [Winlator-Ludashi](https://github.com/StevenMXZ/Winlator-Ludashi) (StevenMXZ): the base of this fork. Inherited from it: presets and post effects, SGSR / FSR / Lanczos 2 / Color Boost, HUD, task manager, gamepad modes, Turnip driver set, and the **Steam integration** (library, login, downloads, cloud, achievements, friends, Goldberg mode, Rust Steam client and launcher), which WinLite rebranded and restyled.
- [WinNative](https://github.com/WinNative-Emu/WinNative): Ludashi credits it for half of the Steam integration, so that credit carries over.
- Goldberg Steam Emulator (optional Goldberg mode).
- **Frame generation:** WFG by [qwertypower](https://github.com/qwertypower) (DEVAR Entertainment LLC), MIT. Built on Dense Inverse Search optical flow (Till Kroeger et al., OpenCV `DISOpticalFlow`) and Farneback optical flow (OpenCV, Apache-2.0). Full attribution in `app/src/main/cpp/wfg/LICENSE`.
- [DirectAudio](https://github.com/The412Banner/directaudio) (The412Banner) · SGSR by Qualcomm · NIS by NVIDIA · force-SSBS approach by Sonicadvance1 (FEX-Emu) and Wine MR 12262
- [Wine](https://www.winehq.org/) · [Box86/Box64](https://github.com/ptitSeb) · [FEX-Emu](https://github.com/FEX-Emu/FEX) · [Mesa](https://www.mesa3d.org) · [DXVK](https://github.com/doitsujin/dxvk) · [VKD3D](https://gitlab.winehq.org/wine/vkd3d) · [D8VK](https://github.com/AlpyneDreams/d8vk) · [CNC DDraw](https://github.com/FunkyFr3sh/cnc-ddraw)

## License
See [LICENSE](LICENSE). The WFG code in `app/src/main/cpp/wfg/` carries its own MIT license (`app/src/main/cpp/wfg/LICENSE`).
