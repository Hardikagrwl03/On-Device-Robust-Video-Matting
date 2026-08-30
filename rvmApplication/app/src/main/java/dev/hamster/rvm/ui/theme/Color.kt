package dev.hamster.rvm.ui.theme

import androidx.compose.ui.graphics.Color

// Brand tonal ramps. Hue 203.5 (deep cyan-teal) for primary/secondary/neutrals,
// hue 62 (warm amber) for tertiary. Tone == CIE L*. Generated once and verified for
// WCAG contrast; see docs/ui-redesign-plan.md "Key decision 3". Do not hand-edit
// individual values - regenerate the whole ramp if the hue ever changes.
internal val RvmPrimary10 = Color(0xFF002022)
internal val RvmPrimary20 = Color(0xFF003739)
internal val RvmPrimary30 = Color(0xFF004F53)
internal val RvmPrimary40 = Color(0xFF00696E)
internal val RvmPrimary80 = Color(0xFF61D7DF)
internal val RvmPrimary90 = Color(0xFFB6ECEF)
internal val RvmPrimary100 = Color(0xFFFFFFFF)

internal val RvmSecondary10 = Color(0xFF002022)
internal val RvmSecondary20 = Color(0xFF153537)
internal val RvmSecondary30 = Color(0xFF2C4C4E)
internal val RvmSecondary40 = Color(0xFF446466)
internal val RvmSecondary80 = Color(0xFFAACDCF)
internal val RvmSecondary90 = Color(0xFFC6E9EB)
internal val RvmSecondary100 = Color(0xFFFFFFFF)

internal val RvmTertiary10 = Color(0xFF2C1700)
internal val RvmTertiary20 = Color(0xFF4D2600)
internal val RvmTertiary30 = Color(0xFF6F3802)
internal val RvmTertiary40 = Color(0xFF8C4F1B)
internal val RvmTertiary80 = Color(0xFFF5BA8F)
internal val RvmTertiary90 = Color(0xFFFEDCC5)
internal val RvmTertiary100 = Color(0xFFFFFFFF)

internal val RvmNeutral0 = Color(0xFF000000)
internal val RvmNeutral4 = Color(0xFF090F10)
internal val RvmNeutral6 = Color(0xFF0F1414)
internal val RvmNeutral10 = Color(0xFF181C1C)
internal val RvmNeutral12 = Color(0xFF1C2021)
internal val RvmNeutral17 = Color(0xFF262B2B)
internal val RvmNeutral20 = Color(0xFF2D3131)
internal val RvmNeutral22 = Color(0xFF313636)
internal val RvmNeutral24 = Color(0xFF363A3A)
internal val RvmNeutral87 = Color(0xFFD5DBDB)
internal val RvmNeutral90 = Color(0xFFDEE3E4)
internal val RvmNeutral92 = Color(0xFFE4E9E9)
internal val RvmNeutral94 = Color(0xFFE9EFEF)
internal val RvmNeutral95 = Color(0xFFECF2F2)
internal val RvmNeutral96 = Color(0xFFEFF5F5)
internal val RvmNeutral98 = Color(0xFFF5FAFB)
internal val RvmNeutral100 = Color(0xFFFFFFFF)

internal val RvmNeutralVariant30 = Color(0xFF3F4849)
internal val RvmNeutralVariant50 = Color(0xFF6F7979)
internal val RvmNeutralVariant60 = Color(0xFF889393)
internal val RvmNeutralVariant80 = Color(0xFFBDC9C9)
internal val RvmNeutralVariant90 = Color(0xFFD9E5E5)

/**
 * Extended colours - deliberately identical in light and dark. Every video preview sits on
 * [RvmVideoPlate] in both themes so the plate never influences how the content on it reads,
 * and so the foreground output (a subject on black) has no bright frame around it.
 */
internal val RvmVideoPlate = RvmNeutral4
internal val RvmOnVideoPlate = RvmNeutral90

/** Scrim alpha for chips/controls drawn over live video. 0.72 keeps text >= 4.5:1 even over a white frame. */
internal const val RvmVideoScrimAlpha = 0.72f
