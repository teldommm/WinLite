#pragma once
// wl.control (wl_proto.h): the 128-byte page WinLite shares with the Vulkan wrapper's WL
// swapchain in the game's process. WinLite creates it before Wine starts and is the only writer
// of the settings and the display clock; the swapchain writes its status there.
//
// One page per process (the game running now), created by WlControl.open from Java; the
// renderer's DisplayClock publishes the refresh grid into it.

#include <cstdint>

#include "../../wl_proto.h"

namespace wlcontrol {

// Creates (truncates) the file and maps it; the settings written so far go in at once. False:
// the file could not be made (the swapchain then runs on its defaults).
bool open(const char* path);

void writeSettings(const wl_control_settings& s);

// The refresh grid: the period and a refresh that really happened (0: none / unknown).
void publishClock(uint64_t periodNs, uint64_t anchorNs);

// False: no page, or no consistent copy.
bool readStatus(wl_control_status& out);

}  // namespace wlcontrol
