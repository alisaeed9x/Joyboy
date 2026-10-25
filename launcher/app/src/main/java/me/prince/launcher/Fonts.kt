package me.prince.launcher

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight

private val WEIGHTS = listOf(FontWeight.Light, FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold)

// res/font/inter.ttf is Inter's variable font (axes: wght 100-900, opsz 14-32), SIL Open Font License
// (third_party/Inter-OFL.txt). Swap in any other variable font with the same axes for a different look.
@OptIn(ExperimentalTextApi::class)
private fun inter(opticalSize: Float) = FontFamily(
    WEIGHTS.map { weight ->
        Font(
            R.font.inter,
            weight,
            variationSettings = FontVariation.Settings(
                FontVariation.weight(weight.weight),
                FontVariation.Setting("opsz", opticalSize),
            ),
        )
    }
)

// Like Apple's SF Pro, Inter has text and display optical sizes; display is for text 20sp and up
val UiText = inter(14f)
val UiDisplay = inter(32f)
