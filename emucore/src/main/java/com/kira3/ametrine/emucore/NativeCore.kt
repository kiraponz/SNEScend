package com.kira3.ametrine.emucore

import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.Surface

/**
 * The emulation core: MesenCE's SNES, through JNI (emucore/src/main/cpp). The native side calls
 * the callbacks at the bottom by name, so the class name and members must not change
 * (consumer-rules.pro keeps them).
 *
 * The functions keep the frontend's signatures; what the SNES has no use for (discs, sticks,
 * memory cards, a BIOS) answers as a core without it would.
 *
 * Use [EmulatorCore] instead of calling this directly.
 */
internal object NativeCore {
    private const val TAG = "NativeCore"

    /** Null when the core library loaded; otherwise why it did not. */
    @JvmStatic
    val loadError: String? = try {
        System.loadLibrary("emucore")
        null
    } catch (e: Throwable) {
        Log.e(TAG, "Could not load the core", e)
        e.message ?: e.toString()
    }

    /** Receives the core's callbacks. Set by EmulatorCore. */
    @Volatile
    @JvmStatic
    var listener: Listener? = null

    /** Opens the core's /saf/ paths. Set by EmulatorCore before [initialize]. */
    @Volatile
    @JvmStatic
    var files: SafFiles? = null

    interface Listener {
        fun onVmPausedChanged(paused: Boolean)
        fun onPadRumble(pad: Int, largeMotor: Int, smallMotor: Int)
    }

    // ---- Setup ----

    /** Once per process. Null on success, or why the core cannot run. */
    @JvmStatic fun initialize(dataDir: String): String? = nativeInitialize(dataDir)
    @JvmStatic fun getBuildVersion(): String = nativeGetBuildVersion()

    // ---- Settings ----

    /** Writes one value to the settings ("Section/Key", see android_host.cpp). A running game applies it on [commitSettings]. */
    @JvmStatic fun setSetting(section: String, key: String, value: String) = nativeSetSetting(section, key, value)
    @JvmStatic fun commitSettings() = nativeCommitSettings()

    // ---- Surface ----

    /** The surface to render to, or null for none. Blocks until the core has let go of the previous one. */
    @JvmStatic fun setSurface(surface: Surface?, width: Int, height: Int) = nativeSetSurface(surface, width, height)

    /** The core paces itself by its own clock (the SNES's 60.1 Hz), whatever the screen's rate. */
    @JvmStatic fun setDisplayRefreshRate(hz: Float) = Unit

    /**
     * Where the picture sits: at the top of a portrait surface when [portraitTop], [topMargin]
     * surface pixels down (clear of the display cutout), else in the middle.
     */
    @JvmStatic fun setDisplayLayout(portraitTop: Boolean, topMargin: Int) = nativeSetDisplayLayout(portraitTop, topMargin)

    /** The core draws nothing over the game, so it has nothing to keep clear. */
    @JvmStatic fun setOsdSafeArea(left: Int, top: Int, right: Int, bottom: Int) = Unit

    // ---- VM lifecycle ----

    /**
     * Boots [path] and runs it until the VM shuts down, from the save state at [saveState] unless
     * it is empty. Blocks the calling thread. Null when it ran, or why it did not boot.
     */
    @JvmStatic fun runSystem(path: String, saveState: String): String? = nativeRunSystem(path, saveState)

    @JvmStatic fun hasActiveSystem(): Boolean = nativeHasActiveSystem()

    @JvmStatic fun pause() = nativePause(true)
    @JvmStatic fun resume() = nativePause(false)
    @JvmStatic fun shutdown() = nativeShutdown()

    // ---- Input and speed ----

    /** Sets button [index] (a [PadButton.code]) of the pad in [port] to [value] (0..1). */
    @JvmStatic fun setPadBind(port: Int, index: Int, value: Float) = nativeSetPadBind(port, index, value)

    /** The SNES pad has no sticks. */
    @JvmStatic fun setPadStick(port: Int, right: Boolean, x: Float, y: Float) = Unit

    @JvmStatic fun setFastForward(enabled: Boolean) = nativeSetFastForward(enabled)

    // ---- Save states, rewind and screenshots ----

    /** Saves the running game to [path]. Blocks. Null when it did, or why not. */
    @JvmStatic fun saveState(path: String): String? = nativeSaveState(path)

    /** Loads the state at [path] into the running game. Blocks. Null when it did, or why not. */
    @JvmStatic fun loadState(path: String): String? = nativeLoadState(path)

    /** The battery save is written when the game stops, so nothing ever holds a save state back. */
    @JvmStatic fun isSavingMemoryCards(): Boolean = false

    /**
     * [serial, PNG screenshot or null] from the header of the state at [path]; null if it is not a
     * state. The core names the game by its ROM, which it is given as `<serial>.<ext>`.
     */
    @JvmStatic fun getSaveStateInfo(path: String): Array<Any?>? {
        val info = nativeGetSaveStateInfo(path) ?: return null
        info[0] = (info[0] as? String)?.substringBeforeLast('.')
        return info
    }

    /** [ages in ms (LongArray), PNG thumbnails (Array<ByteArray?>)] of the rewind points, newest first. */
    @JvmStatic fun getRewindPoints(): Array<Any> = nativeGetRewindPoints()

    /** Goes back to the rewind point [index] (in [getRewindPoints]). Blocks. */
    @JvmStatic fun loadRewindPoint(index: Int): Boolean = nativeLoadRewindPoint(index)

    /** Writes what is on screen to [path] as a PNG, on a thread of the core's own. */
    @JvmStatic fun saveScreenshot(path: String) = nativeSaveScreenshot(path)

    // ---- Cheats ----

    /** The cheats to run in every game from now on: [names] with their code lines in [codes]. */
    @JvmStatic fun setCheats(names: Array<String>, codes: Array<String>) = nativeSetCheats(codes)

    /** True when the core takes [code] (one Game Genie or Pro Action Replay code). */
    @JvmStatic fun isValidCheat(code: String): Boolean = nativeIsValidCheat(code)

    /** Alternating names and code lines of the cheats in a cheat file's [text]; null if it has none. */
    @JvmStatic fun parseCheats(text: String): Array<String>? = CheatFiles.parse(text)

    /** There is no cheat database for the SNES. */
    @JvmStatic fun getDatabaseCheats(serial: String): Array<String>? = null

    // ---- Memory ----

    /** [length] bytes of work RAM from [address]; null with no game or out of range. Blocks. */
    @JvmStatic fun readMemory(address: Int, length: Int): ByteArray? = nativeReadMemory(address, length)

    /** Writes [value] as [size] (1, 2 or 4) bytes at [address] of work RAM. Blocks. */
    @JvmStatic fun writeMemory(address: Int, size: Int, value: Int): Boolean = nativeWriteMemory(address, size, value)

    // ---- Info ----

    @JvmStatic fun getFps(): Float = nativeGetFps()
    @JvmStatic fun getFrameNumber(): Long = nativeGetFrameNumber()

    /** "SNES-xxxxxxxx" from the ROM's CRC32, or null if the file cannot be read. */
    @JvmStatic fun getGameSerial(path: String): String? = nativeGetGameSerial(path)

    // A cartridge has no discs.
    @JvmStatic fun getDiscPlaylist(): Array<String>? = null
    @JvmStatic fun switchDisc(index: Int): Boolean = false
    @JvmStatic fun insertDisc(path: String): Boolean = false
    @JvmStatic fun getDiscSet(serial: String): Array<String>? = null

    /** No game database: the library names games by their files. */
    @JvmStatic fun getGameDbTitle(serial: String): String? = null

    /** The SNES needs no BIOS. Takes ownership of [fd]. */
    @JvmStatic fun getBiosInfoFromFd(fd: Int): String? {
        ParcelFileDescriptor.adoptFd(fd).close()
        return null
    }

    // ---- Native ----

    @JvmStatic private external fun nativeInitialize(dataDir: String): String?
    @JvmStatic private external fun nativeGetBuildVersion(): String
    @JvmStatic private external fun nativeSetSetting(section: String, key: String, value: String)
    @JvmStatic private external fun nativeCommitSettings()
    @JvmStatic private external fun nativeSetSurface(surface: Surface?, width: Int, height: Int)
    @JvmStatic private external fun nativeSetDisplayLayout(portraitTop: Boolean, topMargin: Int)
    @JvmStatic private external fun nativeRunSystem(path: String, saveState: String): String?
    @JvmStatic private external fun nativeHasActiveSystem(): Boolean
    @JvmStatic private external fun nativePause(paused: Boolean)
    @JvmStatic private external fun nativeShutdown()
    @JvmStatic private external fun nativeSetPadBind(port: Int, index: Int, value: Float)
    @JvmStatic private external fun nativeSetFastForward(enabled: Boolean)
    @JvmStatic private external fun nativeSaveState(path: String): String?
    @JvmStatic private external fun nativeLoadState(path: String): String?
    @JvmStatic private external fun nativeGetSaveStateInfo(path: String): Array<Any?>?
    @JvmStatic private external fun nativeGetRewindPoints(): Array<Any>
    @JvmStatic private external fun nativeLoadRewindPoint(index: Int): Boolean
    @JvmStatic private external fun nativeSaveScreenshot(path: String)
    @JvmStatic private external fun nativeSetCheats(codes: Array<String>)
    @JvmStatic private external fun nativeIsValidCheat(code: String): Boolean
    @JvmStatic private external fun nativeReadMemory(address: Int, length: Int): ByteArray?
    @JvmStatic private external fun nativeWriteMemory(address: Int, size: Int, value: Int): Boolean
    @JvmStatic private external fun nativeGetFps(): Float
    @JvmStatic private external fun nativeGetFrameNumber(): Long
    @JvmStatic private external fun nativeGetGameSerial(path: String): String?

    // ---- Called from native ----
    // These run on core threads; nothing may throw out of them.

    @JvmStatic
    fun onVmPausedChanged(paused: Boolean) {
        runCatching { listener?.onVmPausedChanged(paused) }
            .onFailure { Log.e(TAG, "onVmPausedChanged", it) }
    }

    @JvmStatic
    fun safOpen(path: String): Int = runCatching { files?.open(path) }
        .onFailure { Log.e(TAG, "safOpen", it) }.getOrNull() ?: -1
}
