package com.kira3.ametrine.emucore.settings

import kotlin.reflect.KProperty0

/** One value of the core's settings, by the "Section/Key" names android_host.cpp reads. */
internal data class CoreKey(
    val section: String,
    val key: String,
    val value: String,
    /** Dotted paths of the [EmulatorSettings] fields this key is computed from. */
    val fields: Set<String>,
) {
    val id: String get() = "$section/$key"
}

/**
 * Maps [EmulatorSettings] to the core's settings keys. The single place that knows key names;
 * android_host.cpp (ApplySettings) turns them into MesenCE's configs.
 */
internal object CoreKeys {
    fun of(s: EmulatorSettings): List<CoreKey> = KeyWriter().apply { write(s) }.keys

    /** Keys the app fixes whatever the settings say. Written on every apply. None for now. */
    val pinned: List<CoreKey> = emptyList()

    private class KeyWriter {
        val keys = ArrayList<CoreKey>()

        fun put(section: String, key: String, value: String, fields: Set<String> = emptySet()) {
            keys += CoreKey(section, key, value, fields)
        }

        fun group(name: String) = Group(name)

        inner class Group(private val name: String) {
            fun path(property: KProperty0<*>) = "$name.${property.name}"

            fun bool(property: KProperty0<Boolean>, section: String, key: String) =
                put(section, key, property.get().toString(), setOf(path(property)))

            fun int(property: KProperty0<Int>, section: String, key: String, range: IntRange? = null) {
                val value = property.get().let { if (range != null) it.coerceIn(range) else it }
                put(section, key, value.toString(), setOf(path(property)))
            }

            fun string(property: KProperty0<String>, section: String, key: String) =
                put(section, key, property.get(), setOf(path(property)))
        }

        fun write(s: EmulatorSettings) {
            writeConsole(s.console)
            writeDisplay(s.display)
            writeFrameLimit(s.frameLimit)
            writeAudio(s.audio)
            writeRewind(s.rewind)
        }

        private fun writeConsole(c: ConsoleSettings) = with(c) {
            val g = group(EmulatorSettings::console.name)
            g.string(::region, "Snes", "Region")
            g.string(::ramPowerOnState, "Snes", "RamPowerOnState")
            g.int(::overclockScanlines, "Snes", "ExtraScanlinesBeforeNmi", 0..1000)
            g.int(::superFxClockPercent, "Snes", "GsuClockSpeed", 100..1000)
        }

        private fun writeDisplay(d: DisplaySettings) = with(d) {
            val g = group(EmulatorSettings::display.name)
            g.string(::aspectRatio, "Video", "AspectRatio")
            g.string(::scaling, "Video", "Scaling")
            g.string(::filter, "Video", "Filter")
            g.int(::scanlines, "Video", "Scanlines", 0..100)
            g.string(::highResBlend, "Snes", "HighResBlend")
            g.int(::overscanTop, "Snes", "OverscanTop", 0..32)
            g.int(::overscanBottom, "Snes", "OverscanBottom", 0..32)
        }

        private fun writeFrameLimit(f: FrameLimitSettings) = with(f) {
            val g = group(EmulatorSettings::frameLimit.name)
            val speed = if (frameLimitEnable) nominalSpeedPercent.coerceIn(10, 500) else 0
            put("Emulation", "SpeedPercent", speed.toString(), setOf(g.path(::frameLimitEnable), g.path(::nominalSpeedPercent)))
            put("Emulation", "FastForwardPercent", (fastForwardSpeed.coerceIn(0, 10) * 100).toString(), setOf(g.path(::fastForwardSpeed)))
            g.int(::runAheadFrames, "Emulation", "RunAheadFrames", 0..5)
        }

        private fun writeAudio(a: AudioSettings) = with(a) {
            val g = group(EmulatorSettings::audio.name)
            g.int(::volume, "Audio", "Volume", 0..100)
            g.int(::fastForwardVolume, "Audio", "FastForwardVolume", 0..100)
            g.bool(::muted, "Audio", "Muted")
            g.int(::latencyMs, "Audio", "LatencyMs", 15..300)
            g.string(::interpolation, "Snes", "Interpolation")
        }

        private fun writeRewind(r: RewindSettings) = with(r) {
            val g = group(EmulatorSettings::rewind.name)
            g.bool(::enabled, "Rewind", "Enable")
            g.int(::intervalSeconds, "Rewind", "IntervalSeconds", 1..60)
            g.int(::keep, "Rewind", "Keep", 1..20)
        }
    }
}
