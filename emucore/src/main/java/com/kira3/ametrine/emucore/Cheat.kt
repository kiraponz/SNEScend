package com.kira3.ametrine.emucore

import kotlinx.serialization.Serializable

/** One cheat of a game: a name and the code lines the core runs while it is [enabled]. */
@Serializable
data class Cheat(
    val id: String,
    val name: String,
    /** Game Genie ("DD62-6DAD") or Pro Action Replay ("7E0DBF09") codes, one per line. */
    val code: String,
    val enabled: Boolean = false,
)

/** What [SnesCodes.check] found in a cheat's code. */
class CodeCheck(
    /** The code lines, as "XXXX-XXXX" (Game Genie) or "AAAAAAVV" (Pro Action Replay). */
    val lines: List<String>,
    /** The 1-based line the core wouldn't understand, or null when every line is fine. */
    val badLine: Int?,
)

/** Reads the cheat codes the core runs for a SNES game. */
object SnesCodes {
    /** Game Genie: 8 hex digits split 4-4. */
    private val gameGenie = Regex("""^([0-9A-Fa-f]{4})-([0-9A-Fa-f]{4})$""")

    /** Pro Action Replay: a 6-digit address, then a 2-digit value, maybe apart. */
    private val actionReplay = Regex("""^([0-9A-Fa-f]{6})[\s:]*([0-9A-Fa-f]{2})$""")

    /** The code in [line] in the core's form, or null if it is not one. */
    fun normalize(line: String): String? {
        val trimmed = line.trim()
        gameGenie.matchEntire(trimmed)?.let { return "${it.groupValues[1]}-${it.groupValues[2]}".uppercase() }
        actionReplay.matchEntire(trimmed)?.let { return "${it.groupValues[1]}${it.groupValues[2]}".uppercase() }
        return null
    }

    /**
     * Reads [code] line by line; blank lines and comments (`#`, `;`, `//`) are skipped, and a line
     * may hold several codes joined by `+` (as cheat files have them).
     */
    fun check(code: String): CodeCheck {
        val lines = ArrayList<String>()
        var bad: Int? = null
        code.lines().forEachIndexed { index, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";") || line.startsWith("//")) return@forEachIndexed
            val codes = line.split('+').map { normalize(it) }
            if (codes.all { it != null }) {
                lines += codes.filterNotNull()
            } else if (bad == null) {
                bad = index + 1
            }
        }
        return CodeCheck(lines, bad)
    }
}

/** Reads the cheats out of cheat files. */
internal object CheatFiles {
    private val descKey = Regex("""^cheat(\d+)_desc$""")
    private val codeKey = Regex("""^cheat(\d+)_code$""")

    /**
     * Alternating names and code lines of the cheats in [text], a libretro `.cht` file
     * (`cheat0_desc = "..."`, `cheat0_code = "..."`); null if it has none.
     */
    fun parse(text: String): Array<String>? {
        val names = HashMap<Int, String>()
        val codes = HashMap<Int, String>()
        for (raw in text.lines()) {
            val key = raw.substringBefore('=', "").trim()
            if (key.isEmpty()) continue
            val value = raw.substringAfter('=').trim().removeSurrounding("\"")
            descKey.matchEntire(key)?.let { names[it.groupValues[1].toInt()] = value }
            codeKey.matchEntire(key)?.let { codes[it.groupValues[1].toInt()] = value }
        }
        val out = ArrayList<String>()
        for (index in codes.keys.sorted()) {
            val lines = SnesCodes.check(codes.getValue(index).replace('+', '\n')).lines
            if (lines.isEmpty()) continue
            out += names[index]?.takeIf { it.isNotBlank() } ?: "Cheat ${index + 1}"
            out += lines.joinToString("\n")
        }
        return out.takeIf { it.isNotEmpty() }?.toTypedArray()
    }
}
