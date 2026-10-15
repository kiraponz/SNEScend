package com.kira3.ametrine.data.macro

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

/**
 * One recorded input: at frame [t] from the start, [button] goes down ([pressed], at [value]
 * of full pressure) or up. The names are the [com.kira3.ametrine.emucore.PadButton] entries.
 */
@Serializable
data class MacroEvent(
    val t: Long,
    val button: String? = null,
    val pressed: Boolean = false,
    val value: Float = 1f,
)

/**
 * A recording of the pad's input, replayed on the same controls. Time is counted in emulated
 * frames, so it follows the game when it is fast-forwarded or slow. [length] includes the wait
 * after the last input. Macros saved in milliseconds ([frames] false) are converted when read.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Macro(
    val id: String,
    val name: String,
    @JsonNames("durationMs") val length: Long,
    val events: List<MacroEvent>,
    /** Plays again from the start when it ends, until stopped. */
    val loop: Boolean = false,
    val frames: Boolean = false,
    /** Waits for this before each run. */
    val startWhen: MemoryCondition? = null,
    /** Ends the macro (and its loop) as soon as this holds. */
    val stopWhen: MemoryCondition? = null,
    /** Points where playback holds until the game's memory says so, in order of [MacroWait.t]. */
    val waits: List<MacroWait> = emptyList(),
)

/**
 * A pause in a macro at frame [t]: what was recorded before it plays, then playback holds (the
 * clock stands still) until [condition] holds. If [timeout] frames pass first, the macro stops.
 * No [timeout] waits for ever.
 */
@Serializable
data class MacroWait(
    val t: Long,
    val condition: MemoryCondition,
    val timeout: Long? = null,
)

/** This macro with its inputs and wait points in time order, and long enough to hold them. */
fun Macro.normalized(): Macro {
    val sortedEvents = events.sortedBy { it.t }
    val sortedWaits = waits.sortedBy { it.t }
    val last = maxOf(sortedEvents.lastOrNull()?.t ?: 0L, sortedWaits.lastOrNull()?.t ?: 0L)
    return copy(events = sortedEvents, waits = sortedWaits, length = maxOf(length, last))
}

/** Nominal frames per second, to convert the milliseconds of older macros. */
private const val LEGACY_FPS = 60

/** This macro with its times in frames. */
fun Macro.inFrames(): Macro = if (frames) this else copy(
    length = length * LEGACY_FPS / 1000,
    events = events.map { it.copy(t = it.t * LEGACY_FPS / 1000) },
    frames = true,
)

enum class Comparison(val symbol: String) {
    Equal("="), NotEqual("\u2260"), Less("<"), LessOrEqual("\u2264"), Greater(">"), GreaterOrEqual("\u2265"),
}

/** The [size]-byte unsigned number at EE address [address] compared with [value] by [op]. */
@Serializable
data class MemoryCondition(
    val address: Int,
    val size: Int = 4,
    val op: Comparison = Comparison.Equal,
    val value: Long,
) {
    fun holds(current: Long): Boolean = when (op) {
        Comparison.Equal -> current == value
        Comparison.NotEqual -> current != value
        Comparison.Less -> current < value
        Comparison.LessOrEqual -> current <= value
        Comparison.Greater -> current > value
        Comparison.GreaterOrEqual -> current >= value
    }

    /** `0065B93C \u2265 100`. */
    fun describe(): String = "%08X %s %d".format(address, op.symbol, value)
}
