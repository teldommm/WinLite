package com.winlator.cmod.ui.theme

import androidx.compose.ui.graphics.Color

// Colours for everything drawn on top of game artwork (Library pager, game page, launch screen,
// cover tiles). Artwork is always darkened by a scrim first, in every theme, so these are fixed
// light-on-dark values rather than theme colours. They used to be ~20 ad-hoc alphas spread over
// the screens; values that were within a few percent of each other are merged here.
object OnArtwork {
    // Text
    val Text = Color.White
    val TextSecondary = Color.White.copy(alpha = 0.75f)   // container / runtime line, status
    val TextTertiary = Color.White.copy(alpha = 0.62f)    // small caps captions (stat labels)
    val TextShadow = Color.Black.copy(alpha = 0.6f)

    // Glass plates: back / favourite buttons, the game page's action tiles.
    val PlateFill = Color.White.copy(alpha = 0.10f)
    val PlateBorder = Color.White.copy(alpha = 0.16f)

    // A secondary button on artwork ("View details" in the pager).
    val ButtonFill = Color.White.copy(alpha = 0.20f)
    val ButtonBorder = Color.White.copy(alpha = 0.22f)

    // Dark panels: the game page's Play panel, stat chips.
    val PanelFill = Color.Black.copy(alpha = 0.42f)

    // Pager cards.
    val CardFill = Color.Black.copy(alpha = 0.30f)
    val CardBorder = Color.White.copy(alpha = 0.20f)
    val CardBorderSelected = Color.White.copy(alpha = 0.88f)

    // The game's icons in stat chips (playtime, plays, ...): a light blue that reads on dark glass.
    val InfoIcon = Color(0xFF58A6FF)

    // Launch screen status line ("Starting up…"): a little brighter than TextSecondary, it is the
    // only moving information on that screen.
    val LaunchStatus = Color(0xD9FFFFFF)

    // Launch screen scrim, top to bottom (the old loading_art_scrim drawable's stops).
    val LaunchScrim = listOf(Color(0x18000000), Color(0x52000000), Color(0xE6000000))
}

// Dim behind modal dialogs / progress cards (was 0.5f in eight places and 0.6f in one).
val DialogScrimColor = Color.Black.copy(alpha = 0.5f)
