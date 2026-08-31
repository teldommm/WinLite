# Third-Party Credits

This project is distributed under the **GNU General Public License v3.0** (see [LICENSE](LICENSE)).
In compliance with the GPL and the other licenses below, the corresponding source code
for every GPL/copyleft component is available from the upstream projects linked here,
and their copyright and license notices are preserved.

## Frame generation

Frame generation is a port of the Lossless Scaling compute chain to Vulkan. WinNative did not
port it from scratch: it derives from the **Eden Emulator Project**'s port, which in turn derives
from **lsfg-vk**. Both are GPL-3.0-or-later, and both copyright notices are preserved in the
header of every file that carries their work.

| Component | Role | License | Source |
| --- | --- | --- | --- |
| Eden Emulator Project | The Vulkan frame generation chain WinNative's port is derived from | GPL-3.0-or-later | https://git.eden-emu.dev/eden-emu/eden |
| lsfg-vk | The original Vulkan reimplementation, which Eden's port derives from | GPL-3.0-or-later | https://github.com/PancakeTAS/lsfg-vk |
| DXVK (`dxbc`) | Shader translator, used when only DXBC shaders are available | zlib/libpng | https://github.com/doitsujin/dxvk |

The chain layout, the pyramid stages (`lsfg_mipmaps`, `lsfg_alpha`, `lsfg_beta`, `lsfg_gamma`,
`lsfg_delta`, `lsfg_generate`), the Vulkan resource and barrier helpers (`lsfg_common`), the
generation pacer (`lsfg_pacer`) and shader module loading (`lsfg_shaders`) all come from that
lineage. WinNative's own additions are shader extraction from an installed copy of Lossless
Scaling (`lsfg_dll`), DXBC translation (`lsfg_dxbc`), the JNI surface (`lsfg_jni`), driver
probing (`lsfg_probe`) and compositor integration (`vkr_lsfg`).

The frame generation shaders themselves are **not** redistributed. They are read at runtime from
the user's own Lossless Scaling installation, which they must own separately on Steam.

## Supporting libraries

| Component | Role | License | Source |
| --- | --- | --- | --- |
| Oboe | Audio output | Apache-2.0 | https://github.com/google/oboe |
| Winlator | Windows-on-Android base this project forks | GPL-3.0 | https://github.com/brunodev85/winlator |

## Source availability

As required by the GPL-3.0, the complete corresponding source for every copyleft component
is obtainable from the repositories linked in this document, and the bundled license texts
are retained in the source tree of the repository that builds each component.
