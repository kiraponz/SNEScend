package com.kira3.ametrine.emucore

/** What a firmware file the app holds is for. */
data class BiosMetadata(
    /** Firmware has no region; kept for the frontend's BIOS screens, always [BiosRegion.Unknown]. */
    val region: BiosRegion,
    /** What it is for, e.g. "DSP-1 (Pilotwings, Super Mario Kart)". */
    val description: String,
    /** The name the core looks for it by in [EmulatorCore.biosDir]. */
    val fileName: String,
)

/** The console region a BIOS is for. */
enum class BiosRegion {
    Japan,
    Usa,
    Europe,
    Unknown,
}

/** A firmware file a cartridge's coprocessor (or an add-on) needs, as MesenCE's FirmwareHelper names it. */
enum class Firmware(val fileName: String, val sizes: Set<Long>, val description: String) {
    Dsp1("dsp1.rom", setOf(0x2000), "DSP-1 (Pilotwings, Super Mario Kart)"),
    Dsp1b("dsp1b.rom", setOf(0x2000), "DSP-1B (Super Mario Kart, Pilotwings)"),
    Dsp2("dsp2.rom", setOf(0x2000), "DSP-2 (Dungeon Master)"),
    Dsp3("dsp3.rom", setOf(0x2000), "DSP-3 (SD Gundam GX)"),
    Dsp4("dsp4.rom", setOf(0x2000), "DSP-4 (Top Gear 3000)"),
    St010("st010.rom", setOf(0xD000), "ST010 (F1 ROC II)"),
    St011("st011.rom", setOf(0xD000), "ST011 (Hayazashi Nidan Morita Shougi)"),
    St018("st018.rom", setOf(0x28000), "ST018 (Hayazashi Nidan Morita Shougi 2)"),
    Satellaview("BS-X.bin", setOf(0x100000), "Satellaview BIOS (BS-X)"),
    SufamiTurbo("SufamiTurbo.sfc", setOf(0x40000), "Sufami Turbo BIOS"),
    ;

    companion object {
        /**
         * The firmware a file called [name] with [size] bytes is: by its name when that is one the
         * core looks for, else by a size only one firmware has. Null when it is none.
         */
        fun identify(name: String, size: Long): Firmware? {
            entries.firstOrNull { it.fileName.equals(name, ignoreCase = true) }?.let { return it.takeIf { size in it.sizes } }
            return entries.filter { size in it.sizes }.singleOrNull()
        }
    }
}
