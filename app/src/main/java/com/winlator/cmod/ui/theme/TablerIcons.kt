package com.winlator.cmod.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

// The app's icon set: Tabler Icons (https://tabler.io/icons, MIT License, (c) Pawel Kuna), outline
// style - 24 x 24, 2px round-cap strokes - plus the two filled glyphs the favourite / rating states
// need. It replaces androidx.compose.material.icons everywhere, so a call site reads
// `Icon(TablerIcons.Settings, ...)` and every icon is an ImageVector like before: tint, size and
// content description work exactly as with the Material ones.
//
// Strokes are black and are recoloured by Icon(tint = ...). Icons are built lazily, once.
// Path data is the original Tabler data, normalised so every command carries explicit arguments.
object TablerIcons {
    val Check: ImageVector by lazy {
        icon("Check",
            "M5 12 l5 5 l10 -10")
    }

    val Plus: ImageVector by lazy {
        icon("Plus",
            "M12 5 l0 14",
            "M5 12 l14 0")
    }

    val Minus: ImageVector by lazy {
        icon("Minus",
            "M5 12 l14 0")
    }

    val X: ImageVector by lazy {
        icon("X",
            "M18 6 l-12 12",
            "M6 6 l12 12")
    }

    val ChevronDown: ImageVector by lazy {
        icon("ChevronDown",
            "M6 9 l6 6 l6 -6")
    }

    val ChevronRight: ImageVector by lazy {
        icon("ChevronRight",
            "M9 6 l6 6 l-6 6", autoMirror = true)
    }

    val ArrowLeft: ImageVector by lazy {
        icon("ArrowLeft",
            "M5 12 l14 0",
            "M5 12 l6 6",
            "M5 12 l6 -6", autoMirror = true)
    }

    val ArrowBackUp: ImageVector by lazy {
        icon("ArrowBackUp",
            "M9 14 l-4 -4 l4 -4",
            "M5 10 h11 a4 4 0 1 1 0 8 h-1", autoMirror = true)
    }

    val ArrowsMove: ImageVector by lazy {
        icon("ArrowsMove",
            "M18 9 l3 3 l-3 3",
            "M15 12 h6",
            "M6 9 l-3 3 l3 3",
            "M3 12 h6",
            "M9 18 l3 3 l3 -3",
            "M12 15 v6",
            "M15 6 l-3 -3 l-3 3",
            "M12 3 v6")
    }

    val DotsVertical: ImageVector by lazy {
        icon("DotsVertical",
            "M11 12 a1 1 0 1 0 2 0 a1 1 0 1 0 -2 0",
            "M11 19 a1 1 0 1 0 2 0 a1 1 0 1 0 -2 0",
            "M11 5 a1 1 0 1 0 2 0 a1 1 0 1 0 -2 0")
    }

    val GripVertical: ImageVector by lazy {
        icon("GripVertical",
            "M8 5 a1 1 0 1 0 2 0 a1 1 0 1 0 -2 0",
            "M8 12 a1 1 0 1 0 2 0 a1 1 0 1 0 -2 0",
            "M8 19 a1 1 0 1 0 2 0 a1 1 0 1 0 -2 0",
            "M14 5 a1 1 0 1 0 2 0 a1 1 0 1 0 -2 0",
            "M14 12 a1 1 0 1 0 2 0 a1 1 0 1 0 -2 0",
            "M14 19 a1 1 0 1 0 2 0 a1 1 0 1 0 -2 0")
    }

    val Trash: ImageVector by lazy {
        icon("Trash",
            "M4 7 l16 0",
            "M10 11 l0 6",
            "M14 11 l0 6",
            "M5 7 l1 12 a2 2 0 0 0 2 2 h8 a2 2 0 0 0 2 -2 l1 -12",
            "M9 7 v-3 a1 1 0 0 1 1 -1 h4 a1 1 0 0 1 1 1 v3")
    }

    val Pencil: ImageVector by lazy {
        icon("Pencil",
            "M4 20 h4 l10.5 -10.5 a2.828 2.828 0 1 0 -4 -4 l-10.5 10.5 v4",
            "M13.5 6.5 l4 4")
    }

    val Copy: ImageVector by lazy {
        icon("Copy",
            "M7 9.667 a2.667 2.667 0 0 1 2.667 -2.667 h8.666 a2.667 2.667 0 0 1 2.667 2.667 v8.666 a2.667 2.667 0 0 1 -2.667 2.667 h-8.666 a2.667 2.667 0 0 1 -2.667 -2.667 l0 -8.666",
            "M4.012 16.737 a2.005 2.005 0 0 1 -1.012 -1.737 v-10 c0 -1.1 0.9 -2 2 -2 h10 c0.75 0 1.158 0.385 1.5 1")
    }

    val Cut: ImageVector by lazy {
        icon("Cut",
            "M4 17 a3 3 0 1 0 6 0 a3 3 0 1 0 -6 0",
            "M14 17 a3 3 0 1 0 6 0 a3 3 0 1 0 -6 0",
            "M9.15 14.85 l8.85 -10.85",
            "M6 4 l8.85 10.85")
    }

    val Clipboard: ImageVector by lazy {
        icon("Clipboard",
            "M9 5 h-2 a2 2 0 0 0 -2 2 v12 a2 2 0 0 0 2 2 h10 a2 2 0 0 0 2 -2 v-12 a2 2 0 0 0 -2 -2 h-2",
            "M9 5 a2 2 0 0 1 2 -2 h2 a2 2 0 0 1 2 2 a2 2 0 0 1 -2 2 h-2 a2 2 0 0 1 -2 -2")
    }

    val Refresh: ImageVector by lazy {
        icon("Refresh",
            "M20 11 a8.1 8.1 0 0 0 -15.5 -2 m-0.5 -4 v4 h4",
            "M4 13 a8.1 8.1 0 0 0 15.5 2 m0.5 4 v-4 h-4")
    }

    val Search: ImageVector by lazy {
        icon("Search",
            "M3 10 a7 7 0 1 0 14 0 a7 7 0 1 0 -14 0",
            "M21 21 l-6 -6")
    }

    val Settings: ImageVector by lazy {
        icon("Settings",
            "M10.325 4.317 c0.426 -1.756 2.924 -1.756 3.35 0 a1.724 1.724 0 0 0 2.573 1.066 c1.543 -0.94 3.31 0.826 2.37 2.37 a1.724 1.724 0 0 0 1.065 2.572 c1.756 0.426 1.756 2.924 0 3.35 a1.724 1.724 0 0 0 -1.066 2.573 c0.94 1.543 -0.826 3.31 -2.37 2.37 a1.724 1.724 0 0 0 -2.572 1.065 c-0.426 1.756 -2.924 1.756 -3.35 0 a1.724 1.724 0 0 0 -2.573 -1.066 c-1.543 0.94 -3.31 -0.826 -2.37 -2.37 a1.724 1.724 0 0 0 -1.065 -2.572 c-1.756 -0.426 -1.756 -2.924 0 -3.35 a1.724 1.724 0 0 0 1.066 -2.573 c-0.94 -1.543 0.826 -3.31 2.37 -2.37 c1 0.608 2.296 0.07 2.572 -1.065",
            "M9 12 a3 3 0 1 0 6 0 a3 3 0 0 0 -6 0")
    }

    val Adjustments: ImageVector by lazy {
        icon("Adjustments",
            "M4 10 a2 2 0 1 0 4 0 a2 2 0 0 0 -4 0",
            "M6 4 v4",
            "M6 12 v8",
            "M10 16 a2 2 0 1 0 4 0 a2 2 0 0 0 -4 0",
            "M12 4 v10",
            "M12 18 v2",
            "M16 7 a2 2 0 1 0 4 0 a2 2 0 0 0 -4 0",
            "M18 4 v1",
            "M18 9 v11")
    }

    val Home: ImageVector by lazy {
        icon("Home",
            "M5 12 l-2 0 l9 -9 l9 9 l-2 0",
            "M5 12 v7 a2 2 0 0 0 2 2 h10 a2 2 0 0 0 2 -2 v-7",
            "M9 21 v-6 a2 2 0 0 1 2 -2 h2 a2 2 0 0 1 2 2 v6")
    }

    val Apps: ImageVector by lazy {
        icon("Apps",
            "M4 5 a1 1 0 0 1 1 -1 h4 a1 1 0 0 1 1 1 v4 a1 1 0 0 1 -1 1 h-4 a1 1 0 0 1 -1 -1 l0 -4",
            "M4 15 a1 1 0 0 1 1 -1 h4 a1 1 0 0 1 1 1 v4 a1 1 0 0 1 -1 1 h-4 a1 1 0 0 1 -1 -1 l0 -4",
            "M14 15 a1 1 0 0 1 1 -1 h4 a1 1 0 0 1 1 1 v4 a1 1 0 0 1 -1 1 h-4 a1 1 0 0 1 -1 -1 l0 -4",
            "M14 7 l6 0",
            "M17 4 l0 6")
    }

    val LayoutGrid: ImageVector by lazy {
        icon("LayoutGrid",
            "M4 5 a1 1 0 0 1 1 -1 h4 a1 1 0 0 1 1 1 v4 a1 1 0 0 1 -1 1 h-4 a1 1 0 0 1 -1 -1 l0 -4",
            "M14 5 a1 1 0 0 1 1 -1 h4 a1 1 0 0 1 1 1 v4 a1 1 0 0 1 -1 1 h-4 a1 1 0 0 1 -1 -1 l0 -4",
            "M4 15 a1 1 0 0 1 1 -1 h4 a1 1 0 0 1 1 1 v4 a1 1 0 0 1 -1 1 h-4 a1 1 0 0 1 -1 -1 l0 -4",
            "M14 15 a1 1 0 0 1 1 -1 h4 a1 1 0 0 1 1 1 v4 a1 1 0 0 1 -1 1 h-4 a1 1 0 0 1 -1 -1 l0 -4")
    }

    val LayoutList: ImageVector by lazy {
        icon("LayoutList",
            "M4 6 a2 2 0 0 1 2 -2 h12 a2 2 0 0 1 2 2 v2 a2 2 0 0 1 -2 2 h-12 a2 2 0 0 1 -2 -2 l0 -2",
            "M4 16 a2 2 0 0 1 2 -2 h12 a2 2 0 0 1 2 2 v2 a2 2 0 0 1 -2 2 h-12 a2 2 0 0 1 -2 -2 l0 -2")
    }

    val Folder: ImageVector by lazy {
        icon("Folder",
            "M5 4 h4 l3 3 h7 a2 2 0 0 1 2 2 v8 a2 2 0 0 1 -2 2 h-14 a2 2 0 0 1 -2 -2 v-11 a2 2 0 0 1 2 -2")
    }

    val FolderOpen: ImageVector by lazy {
        icon("FolderOpen",
            "M5 19 l2.757 -7.351 a1 1 0 0 1 0.936 -0.649 h12.307 a1 1 0 0 1 0.986 1.164 l-0.996 5.211 a2 2 0 0 1 -1.964 1.625 h-14.026 a2 2 0 0 1 -2 -2 v-11 a2 2 0 0 1 2 -2 h4 l3 3 h7 a2 2 0 0 1 2 2 v2")
    }

    val File: ImageVector by lazy {
        icon("File",
            "M14 3 v4 a1 1 0 0 0 1 1 h4",
            "M17 21 h-10 a2 2 0 0 1 -2 -2 v-14 a2 2 0 0 1 2 -2 h7 l5 5 v11 a2 2 0 0 1 -2 2")
    }

    val FileDescription: ImageVector by lazy {
        icon("FileDescription",
            "M14 3 v4 a1 1 0 0 0 1 1 h4",
            "M17 21 h-10 a2 2 0 0 1 -2 -2 v-14 a2 2 0 0 1 2 -2 h7 l5 5 v11 a2 2 0 0 1 -2 2",
            "M9 17 h6",
            "M9 13 h6")
    }

    val Upload: ImageVector by lazy {
        icon("Upload",
            "M4 17 v2 a2 2 0 0 0 2 2 h12 a2 2 0 0 0 2 -2 v-2",
            "M7 9 l5 -5 l5 5",
            "M12 4 l0 12")
    }

    val Download: ImageVector by lazy {
        icon("Download",
            "M4 17 v2 a2 2 0 0 0 2 2 h12 a2 2 0 0 0 2 -2 v-2",
            "M7 11 l5 5 l5 -5",
            "M12 4 l0 12")
    }

    val CloudUpload: ImageVector by lazy {
        icon("CloudUpload",
            "M7 18 a4.6 4.4 0 0 1 0 -9 a5 4.5 0 0 1 11 2 h1 a3.5 3.5 0 0 1 0 7 h-1",
            "M9 15 l3 -3 l3 3",
            "M12 12 l0 9")
    }

    val Photo: ImageVector by lazy {
        icon("Photo",
            "M15 8 h0.01",
            "M3 6 a3 3 0 0 1 3 -3 h12 a3 3 0 0 1 3 3 v12 a3 3 0 0 1 -3 3 h-12 a3 3 0 0 1 -3 -3 v-12",
            "M3 16 l5 -5 c0.928 -0.893 2.072 -0.893 3 0 l5 5",
            "M14 14 l1 -1 c0.928 -0.893 2.072 -0.893 3 0 l3 3")
    }

    val Music: ImageVector by lazy {
        icon("Music",
            "M3 17 a3 3 0 1 0 6 0 a3 3 0 0 0 -6 0",
            "M13 17 a3 3 0 1 0 6 0 a3 3 0 0 0 -6 0",
            "M9 17 v-13 h10 v13",
            "M9 8 h10")
    }

    val PlayerPlay: ImageVector by lazy {
        icon("PlayerPlay",
            "M7 4 v16 l13 -8 l-13 -8")
    }

    val PlayerPause: ImageVector by lazy {
        icon("PlayerPause",
            "M6 6 a1 1 0 0 1 1 -1 h2 a1 1 0 0 1 1 1 v12 a1 1 0 0 1 -1 1 h-2 a1 1 0 0 1 -1 -1 l0 -12",
            "M14 6 a1 1 0 0 1 1 -1 h2 a1 1 0 0 1 1 1 v12 a1 1 0 0 1 -1 1 h-2 a1 1 0 0 1 -1 -1 l0 -12")
    }

    val PlayerSkipForward: ImageVector by lazy {
        icon("PlayerSkipForward",
            "M4 5 v14 l12 -7 l-12 -7",
            "M20 5 l0 14")
    }

    val DeviceGamepad: ImageVector by lazy {
        icon("DeviceGamepad",
            "M2 8 a2 2 0 0 1 2 -2 h16 a2 2 0 0 1 2 2 v8 a2 2 0 0 1 -2 2 h-16 a2 2 0 0 1 -2 -2 l0 -8",
            "M6 12 h4 m-2 -2 v4",
            "M15 11 l0 0.01",
            "M18 13 l0 0.01")
    }

    val DeviceGamepad2: ImageVector by lazy {
        icon("DeviceGamepad2",
            "M12 5 h3.5 a5 5 0 0 1 0 10 h-5.5 l-4.015 4.227 a2.3 2.3 0 0 1 -3.923 -2.035 l1.634 -8.173 a5 5 0 0 1 4.904 -4.019 h3.4",
            "M14 15 l4.07 4.284 a2.3 2.3 0 0 0 3.925 -2.023 l-1.6 -8.232",
            "M8 9 v2",
            "M7 10 h2",
            "M14 10 h2")
    }

    val DeviceDesktop: ImageVector by lazy {
        icon("DeviceDesktop",
            "M3 5 a1 1 0 0 1 1 -1 h16 a1 1 0 0 1 1 1 v10 a1 1 0 0 1 -1 1 h-16 a1 1 0 0 1 -1 -1 v-10",
            "M7 20 h10",
            "M9 16 v4",
            "M15 16 v4")
    }

    val DeviceLaptop: ImageVector by lazy {
        icon("DeviceLaptop",
            "M3 19 l18 0",
            "M5 7 a1 1 0 0 1 1 -1 h12 a1 1 0 0 1 1 1 v8 a1 1 0 0 1 -1 1 h-12 a1 1 0 0 1 -1 -1 l0 -8")
    }

    val Cpu: ImageVector by lazy {
        icon("Cpu",
            "M5 6 a1 1 0 0 1 1 -1 h12 a1 1 0 0 1 1 1 v12 a1 1 0 0 1 -1 1 h-12 a1 1 0 0 1 -1 -1 l0 -12",
            "M9 9 h6 v6 h-6 l0 -6",
            "M3 10 h2",
            "M3 14 h2",
            "M10 3 v2",
            "M14 3 v2",
            "M21 10 h-2",
            "M21 14 h-2",
            "M14 21 v-2",
            "M10 21 v-2")
    }

    val Database: ImageVector by lazy {
        icon("Database",
            "M4 6 a8 3 0 1 0 16 0 a8 3 0 1 0 -16 0",
            "M4 6 v6 a8 3 0 0 0 16 0 v-6",
            "M4 12 v6 a8 3 0 0 0 16 0 v-6")
    }

    val Server: ImageVector by lazy {
        icon("Server",
            "M3 7 a3 3 0 0 1 3 -3 h12 a3 3 0 0 1 3 3 v2 a3 3 0 0 1 -3 3 h-12 a3 3 0 0 1 -3 -3 v-2",
            "M3 15 a3 3 0 0 1 3 -3 h12 a3 3 0 0 1 3 3 v2 a3 3 0 0 1 -3 3 h-12 a3 3 0 0 1 -3 -3 l0 -2",
            "M7 8 l0 0.01",
            "M7 16 l0 0.01")
    }

    val Terminal: ImageVector by lazy {
        icon("Terminal",
            "M8 9 l3 3 l-3 3",
            "M13 15 l3 0",
            "M3 6 a2 2 0 0 1 2 -2 h14 a2 2 0 0 1 2 2 v12 a2 2 0 0 1 -2 2 h-14 a2 2 0 0 1 -2 -2 l0 -12")
    }

    val Gauge: ImageVector by lazy {
        icon("Gauge",
            "M3 12 a9 9 0 1 0 18 0 a9 9 0 1 0 -18 0",
            "M11 12 a1 1 0 1 0 2 0 a1 1 0 1 0 -2 0",
            "M13.41 10.59 l2.59 -2.59",
            "M7 12 a5 5 0 0 1 5 -5")
    }

    val Shield: ImageVector by lazy {
        icon("Shield",
            "M12 3 a12 12 0 0 0 8.5 3 a12 12 0 0 1 -8.5 15 a12 12 0 0 1 -8.5 -15 a12 12 0 0 0 8.5 -3")
    }

    val Palette: ImageVector by lazy {
        icon("Palette",
            "M12 21 a9 9 0 0 1 0 -18 c4.97 0 9 3.582 9 8 c0 1.06 -0.474 2.078 -1.318 2.828 c-0.844 0.75 -1.989 1.172 -3.182 1.172 h-2.5 a2 2 0 0 0 -1 3.75 a1.3 1.3 0 0 1 -1 2.25",
            "M7.5 10.5 a1 1 0 1 0 2 0 a1 1 0 1 0 -2 0",
            "M11.5 7.5 a1 1 0 1 0 2 0 a1 1 0 1 0 -2 0",
            "M15.5 10.5 a1 1 0 1 0 2 0 a1 1 0 1 0 -2 0")
    }

    val Clock: ImageVector by lazy {
        icon("Clock",
            "M3 12 a9 9 0 1 0 18 0 a9 9 0 0 0 -18 0",
            "M12 7 v5 l3 3")
    }

    val History: ImageVector by lazy {
        icon("History",
            "M12 8 l0 4 l2 2",
            "M3.05 11 a9 9 0 1 1 0.5 4 m-0.5 5 v-5 h5")
    }

    val Bell: ImageVector by lazy {
        icon("Bell",
            "M10 5 a2 2 0 1 1 4 0 a7 7 0 0 1 4 6 v3 a4 4 0 0 0 2 3 h-16 a4 4 0 0 0 2 -3 v-3 a7 7 0 0 1 4 -6",
            "M9 17 v1 a3 3 0 0 0 6 0 v-1")
    }

    val Bug: ImageVector by lazy {
        icon("Bug",
            "M9 9 v-1 a3 3 0 0 1 6 0 v1",
            "M8 9 h8 a6 6 0 0 1 1 3 v3 a5 5 0 0 1 -10 0 v-3 a6 6 0 0 1 1 -3",
            "M3 13 l4 0",
            "M17 13 l4 0",
            "M12 20 l0 -6",
            "M4 19 l3.35 -2",
            "M20 19 l-3.35 -2",
            "M4 7 l3.75 2.4",
            "M20 7 l-3.75 2.4")
    }

    val InfoCircle: ImageVector by lazy {
        icon("InfoCircle",
            "M3 12 a9 9 0 1 0 18 0 a9 9 0 0 0 -18 0",
            "M12 9 h0.01",
            "M11 12 h1 v4 h1")
    }

    val HelpCircle: ImageVector by lazy {
        icon("HelpCircle",
            "M3 12 a9 9 0 1 0 18 0 a9 9 0 0 0 -18 0",
            "M12 16 v0.01",
            "M12 13 a2 2 0 0 0 0.914 -3.782 a1.98 1.98 0 0 0 -2.414 0.483")
    }

    val CircleCheck: ImageVector by lazy {
        icon("CircleCheck",
            "M3 12 a9 9 0 1 0 18 0 a9 9 0 1 0 -18 0",
            "M9 12 l2 2 l4 -4")
    }

    val StackFront: ImageVector by lazy {
        icon("StackFront",
            "M12 4 l-8 4 l8 4 l8 -4 l-8 -4",
            "M8 14 l-4 2 l8 4 l8 -4 l-4 -2",
            "M8 10 l-4 2 l8 4 l8 -4 l-4 -2")
    }

    val Star: ImageVector by lazy {
        icon("Star",
            "M12 17.75 l-6.172 3.245 l1.179 -6.873 l-5 -4.867 l6.9 -1 l3.086 -6.253 l3.086 6.253 l6.9 1 l-5 4.867 l1.179 6.873 l-6.158 -3.245")
    }

    val StarFilled: ImageVector by lazy {
        icon("StarFilled",
            "M8.243 7.34 l-6.38 0.925 l-0.113 0.023 a1 1 0 0 0 -0.44 1.684 l4.622 4.499 l-1.09 6.355 l-0.013 0.11 a1 1 0 0 0 1.464 0.944 l5.706 -3 l5.693 3 l0.1 0.046 a1 1 0 0 0 1.352 -1.1 l-1.091 -6.355 l4.624 -4.5 l0.078 -0.085 a1 1 0 0 0 -0.633 -1.62 l-6.38 -0.926 l-2.852 -5.78 a1 1 0 0 0 -1.794 0 l-2.853 5.78 Z", filled = true)
    }

    val Heart: ImageVector by lazy {
        icon("Heart",
            "M19.5 12.572 l-7.5 7.428 l-7.5 -7.428 a5 5 0 1 1 7.5 -6.566 a5 5 0 1 1 7.5 6.572")
    }

    val HeartFilled: ImageVector by lazy {
        icon("HeartFilled",
            "M6.979 3.074 a6 6 0 0 1 4.988 1.425 l0.037 0.033 l0.034 -0.03 a6 6 0 0 1 4.733 -1.44 l0.246 0.036 a6 6 0 0 1 3.364 10.008 l-0.18 0.185 l-0.048 0.041 l-7.45 7.379 a1 1 0 0 1 -1.313 0.082 l-0.094 -0.082 l-7.493 -7.422 a6 6 0 0 1 3.176 -10.215 Z", filled = true)
    }

    val Wifi: ImageVector by lazy {
        icon("Wifi",
            "M12 18 l0.01 0",
            "M9.172 15.172 a4 4 0 0 1 5.656 0",
            "M6.343 12.343 a8 8 0 0 1 11.314 0",
            "M3.515 9.515 c4.686 -4.687 12.284 -4.687 17 0")
    }

    val WifiOff: ImageVector by lazy {
        icon("WifiOff",
            "M12 18 l0.01 0",
            "M9.172 15.172 a4 4 0 0 1 5.656 0",
            "M6.343 12.343 a7.963 7.963 0 0 1 3.864 -2.14 m4.163 0.155 a7.965 7.965 0 0 1 3.287 2",
            "M3.515 9.515 a12 12 0 0 1 3.544 -2.455 m3.101 -0.92 a12 12 0 0 1 10.325 3.374",
            "M3 3 l18 18")
    }

    val Bolt: ImageVector by lazy {
        icon("Bolt",
            "M13 3 l0 7 l6 0 l-8 11 l0 -7 l-6 0 l8 -11")
    }

    val Cellular: ImageVector by lazy {
        icon("Cellular",
            "M6 18 l0 -3",
            "M10 18 l0 -6",
            "M14 18 l0 -9",
            "M18 18 l0 -12")
    }

    val Network: ImageVector by lazy {
        icon("Network",
            "M6 9 a6 6 0 1 0 12 0 a6 6 0 0 0 -12 0",
            "M12 3 c1.333 0.333 2 2.333 2 6 s-0.667 5.667 -2 6",
            "M12 3 c-1.333 0.333 -2 2.333 -2 6 s0.667 5.667 2 6",
            "M6 9 h12",
            "M3 20 h7",
            "M14 20 h7",
            "M10 20 a2 2 0 1 0 4 0 a2 2 0 0 0 -4 0",
            "M12 15 v3")
    }

    val Trophy: ImageVector by lazy {
        icon("Trophy",
            "M8 21 l8 0",
            "M12 17 l0 4",
            "M7 4 l10 0",
            "M17 4 v8 a5 5 0 0 1 -10 0 v-8",
            "M3 9 a2 2 0 1 0 4 0 a2 2 0 1 0 -4 0",
            "M17 9 a2 2 0 1 0 4 0 a2 2 0 1 0 -4 0")
    }

    val Users: ImageVector by lazy {
        icon("Users",
            "M5 7 a4 4 0 1 0 8 0 a4 4 0 0 0 -8 0",
            "M3 21 v-2 a4 4 0 0 1 4 -4 h4 a4 4 0 0 1 4 4 v2",
            "M16 3.13 a4 4 0 0 1 0 7.75",
            "M21 21 v-2 a4 4 0 0 0 -3 -3.85")
    }

    val Logout: ImageVector by lazy {
        icon("Logout",
            "M14 8 v-2 a2 2 0 0 0 -2 -2 h-7 a2 2 0 0 0 -2 2 v12 a2 2 0 0 0 2 2 h7 a2 2 0 0 0 2 -2 v-2",
            "M9 12 h12 l-3 -3",
            "M18 15 l3 -3")
    }

    val ArrowsSort: ImageVector by lazy {
        icon("ArrowsSort",
            "M3 9 l4 -4 l4 4 m-4 -4 v14",
            "M21 15 l-4 4 l-4 -4 m4 4 v-14")
    }

    val QrCode: ImageVector by lazy {
        icon("QrCode",
            "M4 5 a1 1 0 0 1 1 -1 h4 a1 1 0 0 1 1 1 v4 a1 1 0 0 1 -1 1 h-4 a1 1 0 0 1 -1 -1 l0 -4",
            "M7 17 l0 0.01",
            "M14 5 a1 1 0 0 1 1 -1 h4 a1 1 0 0 1 1 1 v4 a1 1 0 0 1 -1 1 h-4 a1 1 0 0 1 -1 -1 l0 -4",
            "M7 7 l0 0.01",
            "M4 15 a1 1 0 0 1 1 -1 h4 a1 1 0 0 1 1 1 v4 a1 1 0 0 1 -1 1 h-4 a1 1 0 0 1 -1 -1 l0 -4",
            "M17 7 l0 0.01",
            "M14 14 l3 0",
            "M20 14 l0 0.01",
            "M14 14 l0 3",
            "M14 20 l3 0",
            "M17 17 l3 0",
            "M20 17 l0 3")
    }

    val User: ImageVector by lazy {
        icon("User",
            "M8 7 a4 4 0 1 0 8 0 a4 4 0 0 0 -8 0",
            "M6 21 v-2 a4 4 0 0 1 4 -4 h4 a4 4 0 0 1 4 4 v2")
    }

    val Lock: ImageVector by lazy {
        icon("Lock",
            "M5 13 a2 2 0 0 1 2 -2 h10 a2 2 0 0 1 2 2 v6 a2 2 0 0 1 -2 2 h-10 a2 2 0 0 1 -2 -2 l0 -6",
            "M11 16 a1 1 0 1 0 2 0 a1 1 0 0 0 -2 0",
            "M8 11 v-4 a4 4 0 1 1 8 0 v4")
    }

    val Eye: ImageVector by lazy {
        icon("Eye",
            "M10 12 a2 2 0 1 0 4 0 a2 2 0 0 0 -4 0",
            "M21 12 c-2.4 4 -5.4 6 -9 6 c-3.6 0 -6.6 -2 -9 -6 c2.4 -4 5.4 -6 9 -6 c3.6 0 6.6 2 9 6")
    }

    val EyeOff: ImageVector by lazy {
        icon("EyeOff",
            "M10.585 10.587 a2 2 0 0 0 2.829 2.828",
            "M16.681 16.673 a8.717 8.717 0 0 1 -4.681 1.327 c-3.6 0 -6.6 -2 -9 -6 c1.272 -2.12 2.712 -3.678 4.32 -4.674 m2.86 -1.146 a9.055 9.055 0 0 1 1.82 -0.18 c3.6 0 6.6 2 9 6 c-0.666 1.11 -1.379 2.067 -2.138 2.87",
            "M3 3 l18 18")
    }

    val Send: ImageVector by lazy {
        icon("Send",
            "M10 14 l11 -11",
            "M21 3 l-6.5 18 a0.55 0.55 0 0 1 -1 0 l-3.5 -7 l-7 -3.5 a0.55 0.55 0 0 1 0 -1 l18 -6.5")
    }

    // Steam logo (Simple Icons, CC0): a filled brand glyph, drawn with the same tint as the outline set.
    val BrandSteam: ImageVector by lazy {
        icon("BrandSteam",
            "M11.979 0C5.678 0 .511 4.86.022 11.037l6.432 2.658c.545-.371 1.203-.59 1.912-.59.063 0 .125.004.188.006l2.861-4.142V8.91c0-2.495 2.028-4.524 4.524-4.524 2.494 0 4.524 2.031 4.524 4.527s-2.03 4.525-4.524 4.525h-.105l-4.076 2.911c0 .052.004.105.004.159 0 1.875-1.515 3.396-3.39 3.396-1.635 0-3.016-1.173-3.331-2.727L.436 15.27C1.862 20.307 6.486 24 11.979 24c6.627 0 11.999-5.373 11.999-12S18.605 0 11.979 0zM7.54 18.21l-1.473-.61c.262.543.714.999 1.314 1.25 1.297.539 2.793-.076 3.332-1.375.263-.63.264-1.319.005-1.949s-.75-1.121-1.377-1.383c-.624-.26-1.29-.249-1.878-.03l1.523.63c.956.4 1.409 1.5 1.009 2.455-.397.957-1.497 1.41-2.454 1.012H7.54zm11.415-9.303c0-1.662-1.353-3.015-3.015-3.015-1.665 0-3.015 1.353-3.015 3.015 0 1.665 1.35 3.015 3.015 3.015 1.663 0 3.015-1.35 3.015-3.015zm-5.273-.005c0-1.252 1.013-2.266 2.265-2.266 1.249 0 2.266 1.014 2.266 2.266 0 1.251-1.017 2.265-2.266 2.265-1.253 0-2.265-1.014-2.265-2.265z",
            filled = true)
    }

    val DeviceMobile: ImageVector by lazy {
        icon("DeviceMobile",
            "M6 5 a2 2 0 0 1 2 -2 h8 a2 2 0 0 1 2 2 v14 a2 2 0 0 1 -2 2 h-8 a2 2 0 0 1 -2 -2 Z",
            "M11 4 h2",
            "M12 17 l0 0.01")
    }

    val Mouse: ImageVector by lazy {
        icon("Mouse",
            "M6 7 a4 4 0 0 1 4 -4 h4 a4 4 0 0 1 4 4 v10 a4 4 0 0 1 -4 4 h-4 a4 4 0 0 1 -4 -4 Z",
            "M12 7 l0 4")
    }

    private fun icon(
        name: String,
        vararg paths: String,
        filled: Boolean = false,
        autoMirror: Boolean = false
    ): ImageVector = ImageVector.Builder(
        name = "Tabler.$name",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
        autoMirror = autoMirror
    ).apply {
        for (d in paths) {
            addPath(
                pathData = addPathNodes(d),
                fill = if (filled) SolidColor(Color.Black) else null,
                stroke = if (filled) null else SolidColor(Color.Black),
                strokeLineWidth = if (filled) 0f else 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            )
        }
    }.build()
}
