package com.kira3.ametrine.data

import java.io.File
import java.io.FileOutputStream

/**
 * Writing the battery saves in the core's save folder the way the core does
 * (emucore/src/main/cpp/android_host.cpp): through a `.tmp` file that replaces the save only once
 * it is complete, with the replaced save kept as `.bak1` and older ones as `.bak2` and `.bak3`.
 */
internal object BatteryFiles {
    const val BACKUPS = 3

    /** Writes [bytes] to [file] through `<file>.tmp`. False if it could not. */
    fun writeAtomically(file: File, bytes: ByteArray): Boolean {
        val temp = File(file.path + ".tmp")
        return runCatching {
            FileOutputStream(temp).use { out ->
                out.write(bytes)
                out.fd.sync()
            }
            temp.renameTo(file)
        }.getOrDefault(false).also { if (!it) temp.delete() }
    }

    /** Moves the backups of [file] one generation back and copies the file in as the newest. */
    fun backUp(file: File) {
        if (!file.isFile) return
        for (i in BACKUPS - 1 downTo 1) backup(file, i).renameTo(backup(file, i + 1))
        writeAtomically(backup(file, 1), file.readBytes())
    }

    /** The backup [generation] (1 is the newest) of [file]. */
    fun backup(file: File, generation: Int) = File("${file.path}.bak$generation")

    /** [file]'s backups that exist, newest first. */
    fun backups(file: File): List<File> = (1..BACKUPS).map { backup(file, it) }.filter { it.isFile }

    /** Deletes [file] and everything written beside it. */
    fun delete(file: File) {
        file.delete()
        File(file.path + ".tmp").delete()
        for (i in 1..BACKUPS) backup(file, i).delete()
    }
}
