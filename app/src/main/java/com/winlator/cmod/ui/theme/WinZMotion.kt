package com.winlator.cmod.ui.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut

// The app's motion tokens — the counterpart of WinZShapes for time. Animations take their
// duration from here instead of an ad-hoc tween(N) (there used to be eleven different durations
// between 90 and 400ms spread over the screens).
//
// The curve is FastOutSlowIn, the same one tween() uses by default, so moving an existing
// animation onto these tokens keeps how it looks; only durations that were a few ms apart were
// aligned to the nearest step.
object WinZMotion {
    const val DurationS = 150    // exits, small fades
    const val DurationM = 200    // most enters

    // Dialog scrim fade-in: kept at its long-standing 180ms.
    const val DurationScrim = 180

    // Tint of a selected / unselected item (icon, label) — runs alongside the sliding pill.
    const val DurationSelectionTint = 180

    // Main tab switch (MainShell): the incoming tab slides in while the outgoing one slides out.
    const val DurationTabSlide = 260

    // Sliding pill of a segmented switch / chip row (env-variable mode switch, Steam Account/QR).
    const val DurationPill = 260

    private fun <T> spec(durationMillis: Int, delayMillis: Int): FiniteAnimationSpec<T> =
        androidx.compose.animation.core.tween(durationMillis, delayMillis, FastOutSlowInEasing)

    // Names used by the call sites; same curve, they only say which way things move.
    fun <T> enter(durationMillis: Int = DurationM, delayMillis: Int = 0): FiniteAnimationSpec<T> =
        spec(durationMillis, delayMillis)

    fun <T> exit(durationMillis: Int = DurationS, delayMillis: Int = 0): FiniteAnimationSpec<T> =
        spec(durationMillis, delayMillis)

    fun <T> standard(durationMillis: Int = DurationM, delayMillis: Int = 0): FiniteAnimationSpec<T> =
        spec(durationMillis, delayMillis)

    // Sliding selection pill (SlidingPillHost): a spring with almost no overshoot, so the pill
    // settles softly on its item and a quick second tap redirects it without a visible jolt.
    fun <T> selection(): FiniteAnimationSpec<T> =
        androidx.compose.animation.core.spring(dampingRatio = 0.9f, stiffness = 460f)

    // The shared dialog pop (ThemedAlertHost, About, presets, services, saves, controls editor):
    // scrim fades, the card fades and grows from 90%. Same values every dialog already used.
    fun dialogScrimEnter(): EnterTransition = fadeIn(standard(DurationScrim))
    fun dialogScrimExit(): ExitTransition = fadeOut(exit(DurationS))
    fun dialogCardEnter(initialScale: Float = 0.9f): EnterTransition =
        fadeIn(enter(DurationM)) + scaleIn(enter(DurationM), initialScale = initialScale)
    fun dialogCardExit(targetScale: Float = 0.9f): ExitTransition =
        fadeOut(exit(DurationS)) + scaleOut(exit(DurationS), targetScale = targetScale)
}
