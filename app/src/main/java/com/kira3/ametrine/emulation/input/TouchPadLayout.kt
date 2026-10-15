package com.kira3.ametrine.emulation.input

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import com.kira3.ametrine.data.ControlEdit
import com.kira3.ametrine.emucore.PadButton
import kotlin.math.min

/** One control on the touch pad, in pixels of the pad's area. */
internal sealed interface TouchControl {
    /** A button, round when [round] and pill-shaped otherwise. [scale] is how much a layout edit enlarged it. */
    data class Button(val button: PadButton, val bounds: Rect, val round: Boolean, val scale: Float = 1f) : TouchControl

    data class DPad(val center: Offset, val radius: Float) : TouchControl
}

/** The buttons the pad does not draw itself: the caller supplies them and the layout says where they go. */
internal enum class PadExtra(val id: String) {
    FastForward("extra.fastForward"),
    Rewind("extra.rewind"),
    Screenshot("extra.screenshot"),
    Macro("extra.macro"),
    Ai("extra.ai"),
}

/** The key of a control in a layout's edits. */
internal val TouchControl.id: String
    get() = when (this) {
        is TouchControl.Button -> "btn.${button.name}"
        is TouchControl.DPad -> "dpad"
    }

/**
 * [extras] are the centers of the buttons the pad does not draw itself, [extraScales] how much they are
 * enlarged. Rewind is kept well away from the shoulder buttons, so it is not pressed by a slip.
 */
internal class TouchPadLayout(
    val unit: Float,
    val controls: List<TouchControl>,
    val extras: Map<PadExtra, Offset>,
    val extraScales: Map<PadExtra, Float> = emptyMap(),
) {
    /** The center of the control with this [id] (a [TouchControl.id] or a [PadExtra.id]), if the layout has it. */
    fun centerOf(id: String): Offset? =
        controls.firstOrNull { it.id == id }?.let {
            when (it) {
                is TouchControl.Button -> it.bounds.center
                is TouchControl.DPad -> it.center
            }
        } ?: PadExtra.entries.firstOrNull { it.id == id }?.let { extras[it] }

    /** This layout with the user's [edits] applied; [size] is the area it was laid out for. */
    fun edited(edits: Map<String, ControlEdit>, size: Size): TouchPadLayout {
        if (edits.isEmpty()) return this
        fun shift(edit: ControlEdit) = Offset(edit.x * size.width, edit.y * size.height)
        return TouchPadLayout(
            unit,
            controls.map { control ->
                val edit = edits[control.id] ?: return@map control
                val s = edit.scale
                when (control) {
                    is TouchControl.Button -> {
                        val bounds = Rect(
                            control.bounds.center + shift(edit) - Offset(control.bounds.width * s / 2, control.bounds.height * s / 2),
                            Size(control.bounds.width * s, control.bounds.height * s),
                        )
                        control.copy(bounds = bounds, scale = s)
                    }
                    is TouchControl.DPad -> control.copy(center = control.center + shift(edit), radius = control.radius * s)
                }
            },
            extras.mapValues { (extra, center) -> edits[extra.id]?.let { center + shift(it) } ?: center },
            PadExtra.entries.mapNotNull { extra -> edits[extra.id]?.let { extra to it.scale } }.toMap(),
        )
    }
}

/**
 * The function buttons alone, for while the touch pad is hidden: in a row at the top left, or the bottom
 * left upright (where the picture is at the top).
 */
internal fun controllerLayout(size: Size, maxUnit: Float): TouchPadLayout {
    val u = touchPadLayout(size, maxUnit).unit
    val m = 0.35f * u
    val gap = 1.25f * u
    val y = if (size.width >= size.height) m + 0.8f * u else size.height - m - 1.3f * u
    val first = m + 0.8f * u
    return TouchPadLayout(
        u,
        emptyList(),
        PadExtra.entries.withIndex().associate { (i, extra) -> extra to Offset(first + i * gap, y) },
    )
}

/**
 * Places the controls for an area of [size]. Everything is sized from one unit, so the pad
 * scales with the screen; [maxUnit] keeps it from growing huge on tablets.
 *
 * Landscape puts the D-pad and the face buttons on either side of the picture, L and R at the top
 * corners, as on a Super Famicom pad. Portrait stacks them under the picture, which the core draws
 * at the top of the screen.
 */
internal fun touchPadLayout(size: Size, maxUnit: Float): TouchPadLayout =
    if (size.width >= size.height) landscapeLayout(size, maxUnit) else portraitLayout(size, maxUnit)

private fun landscapeLayout(size: Size, maxUnit: Float): TouchPadLayout {
    val (w, h) = size
    val u = min(min(h / 6.5f, w / 14f), maxUnit)
    val m = 0.35f * u
    val clusterY = h - m - 2.4f * u
    val shoulder = Size(2.2f * u, 0.85f * u)
    val extraGap = 1.25f * u

    return TouchPadLayout(
        u,
        buildList {
            add(pill(PadButton.L, Offset(m, m), shoulder))
            add(pill(PadButton.R, Offset(w - m - shoulder.width, m), shoulder))

            add(TouchControl.DPad(Offset(m + 1.6f * u, clusterY), 1.5f * u))
            addAll(faceButtons(Offset(w - m - 1.6f * u, clusterY), u))

            addAll(selectStart(Offset(w / 2, h - m - 0.35f * u), u))
        },
        // Along the top between L and R.
        mapOf(
            PadExtra.FastForward to Offset(w / 2, m + shoulder.height / 2),
            PadExtra.Rewind to Offset(w / 2 - 2 * extraGap, m + shoulder.height / 2),
            PadExtra.Macro to Offset(w / 2 - extraGap, m + shoulder.height / 2),
            PadExtra.Screenshot to Offset(w / 2 + extraGap, m + shoulder.height / 2),
            PadExtra.Ai to Offset(w / 2 + 2 * extraGap, m + shoulder.height / 2),
        ),
    )
}

private fun portraitLayout(size: Size, maxUnit: Float): TouchPadLayout {
    val (w, h) = size
    val u = min(w / 9.6f, maxUnit)
    val m = 0.35f * u
    val top = h - m - 6.2f * u
    val clusterY = top + 3.4f * u
    val shoulder = Size(2.2f * u, 0.85f * u)

    return TouchPadLayout(
        u,
        buildList {
            add(pill(PadButton.L, Offset(m, top), shoulder))
            add(pill(PadButton.R, Offset(w - m - shoulder.width, top), shoulder))

            add(TouchControl.DPad(Offset(m + 1.6f * u, clusterY), 1.5f * u))
            addAll(faceButtons(Offset(w - m - 1.6f * u, clusterY), u))

            addAll(selectStart(Offset(w / 2, h - m - 0.6f * u), u))
        },
        mapOf(
            // Between L and R.
            PadExtra.FastForward to Offset(w / 2, top + shoulder.height / 2),
            // Above L, in the empty space under the picture.
            PadExtra.Rewind to Offset(m + shoulder.width / 2, top - 1.4f * u),
            // Above R, mirroring rewind.
            PadExtra.Screenshot to Offset(w - m - shoulder.width / 2, top - 1.4f * u),
            // Beside rewind.
            PadExtra.Macro to Offset(m + shoulder.width / 2 + 1.4f * u, top - 1.4f * u),
            // Beside screenshot.
            PadExtra.Ai to Offset(w - m - shoulder.width / 2 - 1.4f * u, top - 1.4f * u),
        ),
    )
}

/** X at the top, A at the right, B at the bottom and Y at the left, as on the SNES pad. */
private fun faceButtons(center: Offset, u: Float): List<TouchControl> {
    val d = 0.95f * u
    val r = 0.6f * u
    return listOf(
        round(PadButton.X, center + Offset(0f, -d), r),
        round(PadButton.A, center + Offset(d, 0f), r),
        round(PadButton.B, center + Offset(0f, d), r),
        round(PadButton.Y, center + Offset(-d, 0f), r),
    )
}

private fun selectStart(center: Offset, u: Float): List<TouchControl> {
    val size = Size(1.3f * u, 0.55f * u)
    val dx = 0.8f * u
    return listOf(
        pill(PadButton.Select, center + Offset(-dx - size.width / 2, -size.height / 2), size),
        pill(PadButton.Start, center + Offset(dx - size.width / 2, -size.height / 2), size),
    )
}

private fun pill(button: PadButton, topLeft: Offset, size: Size) =
    TouchControl.Button(button, Rect(topLeft, size), round = false)

private fun round(button: PadButton, center: Offset, radius: Float) =
    TouchControl.Button(button, Rect(center, radius), round = true)
