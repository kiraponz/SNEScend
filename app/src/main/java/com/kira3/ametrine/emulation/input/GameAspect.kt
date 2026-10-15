package com.kira3.ametrine.emulation.input

import com.kira3.ametrine.emucore.settings.DisplaySettings

/**
 * The width / height of the picture for [display]'s aspect ratio: 256 pixels across the lines the
 * overscan leaves (of the console's 239), shaped by the pixels' own aspect (8:7 on the console, square
 * without stretching) or by a fixed screen shape.
 */
fun gameAspectOf(display: DisplaySettings): Float? {
    val lines = (239 - display.overscanTop.coerceIn(0, 32) - display.overscanBottom.coerceIn(0, 32)).toFloat()
    return when (display.aspectRatio) {
        "NoStretching" -> 256f / lines
        "Standard" -> 4f / 3f
        "Widescreen" -> 16f / 9f
        else -> 256f * 8f / 7f / lines
    }
}
