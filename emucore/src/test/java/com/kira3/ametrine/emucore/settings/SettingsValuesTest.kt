package com.kira3.ametrine.emucore.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsValuesTest {
    @Test
    fun readsEachType() {
        val values = SettingsValues(EmulatorSettings())
        assertEquals(false, values["audio.muted"])
        assertEquals(100, values["audio.volume"])
        assertEquals("Auto", values["console.region"])
    }

    @Test
    fun writesByPath() {
        val values = SettingsValues(EmulatorSettings())
        assertEquals(true, values.with("audio.muted", true).audio.muted)
        assertEquals(30, values.with("audio.volume", 30).audio.volume)
        assertEquals("PAL", values.with("console.region", "PAL").console.region)
    }

    @Test
    fun wrongTypeIsIgnored() {
        assertEquals(EmulatorSettings(), SettingsValues(EmulatorSettings()).with("audio.volume", "loud"))
    }

    @Test(expected = Exception::class)
    fun unknownPathThrows() {
        SettingsValues(EmulatorSettings())["audio.nope"]
    }
}
