package com.kira3.ametrine.emucore

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SnesCodesTest {
    @Test
    fun readsCodeLines() {
        val check = SnesCodes.check("dd62-6dad\n  7e0dbf09 \n7E0DBF:63+C2A4-6F6D")
        assertNull(check.badLine)
        assertEquals(listOf("DD62-6DAD", "7E0DBF09", "7E0DBF63", "C2A4-6F6D"), check.lines)
    }

    @Test
    fun skipsBlankLinesAndComments() {
        val check = SnesCodes.check("# infinite lives\n\n; note\n// more\n7E0DBF09")
        assertNull(check.badLine)
        assertEquals(listOf("7E0DBF09"), check.lines)
    }

    @Test
    fun pointsAtTheFirstBadLine() {
        val check = SnesCodes.check("7E0DBF09\n8009C6E4 0FFF\nDD62-6DA")
        assertEquals(2, check.badLine)
        assertEquals(listOf("7E0DBF09"), check.lines)
    }

    @Test
    fun readsLibretroCheatFiles() {
        val text = """
            cheats = 2

            cheat0_desc = "Infinite lives"
            cheat0_code = "7E0DBF09"
            cheat0_enable = false

            cheat1_desc = "Start with cape"
            cheat1_code = "7E0019:02+DD62-6DAD"
        """.trimIndent()
        assertArrayEquals(
            arrayOf("Infinite lives", "7E0DBF09", "Start with cape", "7E001902\nDD62-6DAD"),
            CheatFiles.parse(text),
        )
    }
}
