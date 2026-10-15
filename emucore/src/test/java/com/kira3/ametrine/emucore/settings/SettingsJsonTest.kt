package com.kira3.ametrine.emucore.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsJsonTest {
    @Test
    fun roundTrips() {
        val settings = EmulatorSettings(
            console = ConsoleSettings(region = "PAL", overclockScanlines = 120),
            display = DisplaySettings(scaling = "Nearest", scanlines = 30),
        )
        assertEquals(settings, SettingsJson.decode(SettingsJson.parse(SettingsJson.format(SettingsJson.encode(settings)))))
    }

    @Test
    fun badValuesFallBackToDefaults() {
        val stored = SettingsJson.parse(
            """{"display": {"scanlines": 1.5, "filter": 3, "overscanTop": 4}, "audio": {"muted": "no"}, "gone": {"x": 1}}""",
        )
        val settings = SettingsJson.decode(stored)
        assertEquals(0, settings.display.scanlines)
        assertEquals("None", settings.display.filter)
        assertEquals(4, settings.display.overscanTop)
        assertEquals(false, settings.audio.muted)
    }

    @Test
    fun unreadableFileIsDefaults() {
        assertEquals(EmulatorSettings(), SettingsJson.decode(SettingsJson.parse("not json")))
    }

    @Test
    fun overridesRecordEveryEditedValue() {
        val global = EmulatorSettings()
        val edited = global.copy(audio = global.audio.copy(muted = true))
        val overrides = SettingsOverrides().record(global, edited)
        assertEquals(setOf("audio.muted"), overrides.paths)
        assertEquals(edited, SettingsJson.merge(global, overrides))

        // An override equal to the global value stays: the player chose it for this game.
        val back = overrides.record(edited, global)
        assertEquals(setOf("audio.muted"), back.paths)

        // A later global change reaches only the values the game does not override.
        val newGlobal = global.copy(audio = global.audio.copy(muted = true, volume = 40))
        val merged = SettingsJson.merge(newGlobal, back)
        assertEquals(false, merged.audio.muted)
        assertEquals(40, merged.audio.volume)

        assertTrue(back.without(listOf("audio.muted")).isEmpty())
    }

    @Test
    fun keysCarryTheirFields() {
        val keys = CoreKeys.of(EmulatorSettings()).associateBy { it.id }
        assertEquals(setOf("console.region"), keys.getValue("Snes/Region").fields)
        assertEquals("Auto", keys.getValue("Snes/Region").value)
        assertEquals("Sharp", keys.getValue("Video/Scaling").value)
        assertEquals("100", keys.getValue("Emulation/SpeedPercent").value)
        // No key is written twice, and none clashes with a pinned one.
        assertEquals(keys.size, CoreKeys.of(EmulatorSettings()).size)
        assertTrue(CoreKeys.pinned.none { it.id in keys })
    }

    @Test
    fun speedsAreComputed() {
        val keys = CoreKeys.of(
            EmulatorSettings(frameLimit = FrameLimitSettings(frameLimitEnable = false, fastForwardSpeed = 3)),
        ).associateBy { it.id }
        assertEquals("0", keys.getValue("Emulation/SpeedPercent").value)
        assertEquals("300", keys.getValue("Emulation/FastForwardPercent").value)
    }

    @Test
    fun copyPathsKeepsOnlyThosePaths() {
        val boot = EmulatorSettings(console = ConsoleSettings(region = "NTSC"))
        val now = EmulatorSettings(console = ConsoleSettings(region = "PAL", overclockScanlines = 100))
        val held = SettingsJson.copyPaths(now, boot, SettingsEffects.appliesOnNextBoot)
        assertEquals("NTSC", held.console.region)
        assertEquals(100, held.console.overclockScanlines)
    }

    @Test
    fun nextBootPathsExist() {
        val all = leafPaths(SettingsJson.encode(EmulatorSettings()))
        for (path in SettingsEffects.appliesOnNextBoot) assertTrue(path, path in all)
    }

    private fun leafPaths(obj: kotlinx.serialization.json.JsonObject, prefix: String = ""): Set<String> = buildSet {
        for ((key, value) in obj) {
            if (value is kotlinx.serialization.json.JsonObject) addAll(leafPaths(value, "$prefix$key."))
            else add(prefix + key)
        }
    }
}
