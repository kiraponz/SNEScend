package com.kira3.ametrine.emucore

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The emulation core. In this template it is a stub written in Kotlin: it "boots" any file, draws
 * a test picture with the pad state to the surface, and keeps enough state (a frame counter, a
 * RAM, save states, rewind points, a disc drive) for every screen of the frontend to work.
 *
 * To put a real core in, keep these signatures and make them `external` (JNI), the way Aegirine
 * does with aegirine-core: the native side calls the callbacks at the bottom by name, so the
 * class name and members must not change then (consumer-rules.pro keeps them).
 *
 * Use [EmulatorCore] instead of calling this directly.
 */
internal object NativeCore {
    private const val TAG = "NativeCore"

    /** Null when the core library loaded; otherwise why it did not. */
    @JvmStatic
    val loadError: String? = null

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

    // ---- Stub state ----

    private const val FRAME_MS = 1000L / 60
    private const val STATE_MAGIC = "STUBSTATE1"
    private const val RAM_BYTES = 2 * 1024 * 1024
    private const val REWIND_EVERY_FRAMES = 60
    private const val REWIND_POINTS = 20

    /** Guards the surface: [setSurface] waits here until the frame being drawn is done. */
    private val surfaceLock = ReentrantLock()
    private var surface: Surface? = null

    private val vmLock = Object()

    @Volatile private var active = false
    @Volatile private var paused = false
    @Volatile private var stopRequested = false
    @Volatile private var fastForward = false
    @Volatile private var frame = 0L
    @Volatile private var fps = 0f
    @Volatile private var gameName = ""

    private val ram = ByteArray(RAM_BYTES)

    /** Bind values by [PadButton.code] (the half axes after the buttons are unused here). */
    private val binds = Array(2) { FloatArray(32) }
    private val sticks = Array(2) { FloatArray(4) }
    private var lastRumble = 0 to 0

    private var cheats: List<String> = emptyList()

    private class RewindPoint(val time: Long, val frame: Long, val ram: ByteArray, val thumbnail: ByteArray?)

    private val rewind = ArrayDeque<RewindPoint>()

    /** The playlist's entries (titles), or null when the game is not a .m3u. */
    @Volatile private var playlist: List<String>? = null
    @Volatile private var disc = 0
    @Volatile private var discLabel = ""

    // ---- Setup ----

    /** Once per process. Null on success, or why the core cannot run. */
    @JvmStatic fun initialize(dataDir: String): String? = null
    @JvmStatic fun getBuildVersion(): String = "stub"

    // ---- Settings ----

    /** Writes one value to the base settings layer. A running VM applies it on [commitSettings]. */
    @JvmStatic fun setSetting(section: String, key: String, value: String) = Unit
    @JvmStatic fun commitSettings() = Unit

    // ---- Surface ----

    /** The surface to render to, or null for none. Blocks until the core has let go of the previous one. */
    @JvmStatic fun setSurface(surface: Surface?, width: Int, height: Int) = surfaceLock.withLock {
        this.surface = surface
    }

    @JvmStatic fun setDisplayRefreshRate(hz: Float) = Unit

    /**
     * Where the picture sits: at the top of a portrait surface when [portraitTop], [topMargin]
     * surface pixels down (clear of the display cutout), else in the middle.
     */
    @JvmStatic fun setDisplayLayout(portraitTop: Boolean, topMargin: Int) = Unit

    /** The edges of the surface the on-screen messages and overlays keep clear of, in surface pixels. */
    @JvmStatic fun setOsdSafeArea(left: Int, top: Int, right: Int, bottom: Int) = Unit

    // ---- VM lifecycle ----

    /**
     * Boots [path] (empty for the BIOS alone) and runs it until the VM shuts down, from the save
     * state at [saveState] unless it is empty. Blocks the calling thread. Null when it booted, or
     * why it did not.
     */
    @JvmStatic fun runSystem(path: String, saveState: String): String? {
        frame = 0
        ram.fill(0)
        rewind.clear()
        binds.forEach { it.fill(0f) }
        sticks.forEach { it.fill(0f) }
        gameName = if (path.isEmpty()) "BIOS" else path.substringAfterLast('/')
        playlist = if (path.endsWith(".m3u", ignoreCase = true)) readPlaylist(path) else null
        disc = 0
        discLabel = playlist?.firstOrNull() ?: gameName
        stopRequested = false
        paused = false
        fastForward = false
        active = true
        if (saveState.isNotEmpty()) loadState(saveState)?.let { Log.w(TAG, "Boot state: $it") }
        onVmPausedChanged(false)

        var last = SystemClock.elapsedRealtime()
        var fpsFrames = 0
        var fpsSince = last
        try {
            while (!stopRequested) {
                synchronized(vmLock) {
                    while (paused && !stopRequested) vmLock.wait()
                }
                if (stopRequested) break
                stepFrame()
                drawToSurface()
                fpsFrames++
                val now = SystemClock.elapsedRealtime()
                if (now - fpsSince >= 1000) {
                    fps = fpsFrames * 1000f / (now - fpsSince)
                    fpsFrames = 0
                    fpsSince = now
                }
                if (!fastForward) {
                    val wait = FRAME_MS - (now - last)
                    if (wait > 0) Thread.sleep(wait)
                }
                last = SystemClock.elapsedRealtime()
            }
        } finally {
            active = false
            rumble(0, 0)
        }
        return null
    }

    @JvmStatic fun hasActiveSystem(): Boolean = active

    @JvmStatic fun pause() = setPaused(true)
    @JvmStatic fun resume() = setPaused(false)

    @JvmStatic fun shutdown() = synchronized(vmLock) {
        stopRequested = true
        vmLock.notifyAll()
    }

    private fun setPaused(value: Boolean) {
        if (!active) return
        synchronized(vmLock) {
            if (paused == value) return
            paused = value
            vmLock.notifyAll()
        }
        if (value) rumble(0, 0) else lastRumble = -1 to -1
        onVmPausedChanged(value)
    }

    /** Runs one frame of the "game": counts it into RAM, runs the cheats and keeps rewind points. */
    private fun stepFrame() = synchronized(vmLock) {
        frame++
        writeRam(0, 4, frame.toInt())
        // "AAAAAAAA VVVV" lines: write the value to the address, as a constant-write code does.
        for (line in cheats) {
            val parts = line.split(' ')
            val address = parts[0].toLongOrNull(16)?.toInt()?.and(0x00FFFFFF) ?: continue
            val value = parts.getOrNull(1)?.toLongOrNull(16)?.toInt() ?: continue
            writeRam(address, if (parts[1].length == 8) 4 else 2, value)
        }
        if (frame % REWIND_EVERY_FRAMES == 0L) {
            rewind.addFirst(RewindPoint(SystemClock.elapsedRealtime(), frame, ram.copyOf(), png(render(160, 120))))
            while (rewind.size > REWIND_POINTS) rewind.removeLast()
        }
        // L2 and R2 drive the motors, to try the rumble.
        rumble((binds[0][PadButton.L2.code] * 255).toInt(), (binds[0][PadButton.R2.code] * 255).toInt())
    }

    private fun rumble(large: Int, small: Int) {
        if (lastRumble == large to small) return
        lastRumble = large to small
        onPadRumble(0, large, small)
    }

    // ---- Input and speed ----

    /** Sets bind [index] (a [PadButton.code], or a half axis after them) of the pad in [port] to [value] (0..1). */
    @JvmStatic fun setPadBind(port: Int, index: Int, value: Float) {
        binds.getOrNull(port)?.let { if (index in it.indices) it[index] = value }
    }

    /** Moves the left or [right] stick of the pad in [port] to ([x], [y]), each -1..1 with +y down. */
    @JvmStatic fun setPadStick(port: Int, right: Boolean, x: Float, y: Float) {
        val s = sticks.getOrNull(port) ?: return
        val i = if (right) 2 else 0
        s[i] = x
        s[i + 1] = y
    }

    @JvmStatic fun setFastForward(enabled: Boolean) {
        fastForward = enabled
    }

    // ---- Save states, rewind and screenshots ----

    /** Saves the running game to [path]. Blocks. Null when it did, or why not. */
    @JvmStatic fun saveState(path: String): String? {
        if (!active) return "No game is running"
        val (savedFrame, savedRam) = synchronized(vmLock) { frame to ram.copyOf() }
        return runCatching {
            DataOutputStream(File(path).outputStream().buffered()).use { out ->
                out.writeUTF(STATE_MAGIC)
                out.writeUTF(serialOf(gameName))
                val shot = png(render(320, 240)) ?: ByteArray(0)
                out.writeInt(shot.size)
                out.write(shot)
                out.writeLong(savedFrame)
                out.write(savedRam)
            }
        }.exceptionOrNull()?.let { it.message ?: it.toString() }
    }

    /** Loads the state at [path] into the running game. Blocks. Null when it did, or why not. */
    @JvmStatic fun loadState(path: String): String? = runCatching {
        DataInputStream(File(path).inputStream().buffered()).use { input ->
            check(input.readUTF() == STATE_MAGIC) { "Not a save state" }
            input.readUTF()
            input.skipBytes(input.readInt())
            val savedFrame = input.readLong()
            val savedRam = ByteArray(RAM_BYTES).also { input.readFully(it) }
            synchronized(vmLock) {
                frame = savedFrame
                savedRam.copyInto(ram)
            }
        }
    }.exceptionOrNull()?.let { it.message ?: it.toString() }

    /** True while a memory card is waiting to be written, which refuses a save state. */
    @JvmStatic fun isSavingMemoryCards(): Boolean = false

    /** [serial, PNG screenshot or null] from the header of the state at [path]; null if it is not a state. */
    @JvmStatic fun getSaveStateInfo(path: String): Array<Any?>? = runCatching {
        DataInputStream(File(path).inputStream().buffered()).use { input ->
            if (input.readUTF() != STATE_MAGIC) return null
            val serial = input.readUTF()
            val shot = ByteArray(input.readInt()).also { input.readFully(it) }
            arrayOf<Any?>(serial, shot.takeIf { it.isNotEmpty() })
        }
    }.getOrNull()

    /** [ages in ms (LongArray), PNG thumbnails (Array<ByteArray?>)] of the rewind points, newest first. */
    @JvmStatic fun getRewindPoints(): Array<Any> = synchronized(vmLock) {
        val now = SystemClock.elapsedRealtime()
        arrayOf(LongArray(rewind.size) { now - rewind[it].time }, Array(rewind.size) { rewind[it].thumbnail })
    }

    /** Goes back to the rewind point [index] (in [getRewindPoints]). Blocks. */
    @JvmStatic fun loadRewindPoint(index: Int): Boolean = synchronized(vmLock) {
        val point = rewind.getOrNull(index) ?: return false
        frame = point.frame
        point.ram.copyInto(ram)
        repeat(index) { rewind.removeFirst() }
        true
    }

    /** Writes what is on screen to [path] as a PNG, on a thread of the core's own. */
    @JvmStatic fun saveScreenshot(path: String) {
        Thread {
            runCatching { png(render(640, 480))?.let { File(path).writeBytes(it) } }
                .onFailure { Log.e(TAG, "saveScreenshot", it) }
        }.start()
    }

    // ---- Cheats ----

    /** The cheats to run in every game from now on: [names] with their code lines in [codes]. */
    @JvmStatic fun setCheats(names: Array<String>, codes: Array<String>) = synchronized(vmLock) {
        cheats = codes.flatMap { it.lines() }.filter { it.isNotBlank() }
    }

    /** Alternating names and code lines of the cheats in a cheat file's [text]; null if it has none. */
    @JvmStatic fun parseCheats(text: String): Array<String>? = null

    /** Alternating names and code lines of the cheat database's cheats for [serial]; null if it has none. Needs [initialize]. */
    @JvmStatic fun getDatabaseCheats(serial: String): Array<String>? = null

    // ---- Memory ----

    /** [length] bytes of main RAM from [address]; null with no game or out of range. Blocks. */
    @JvmStatic fun readMemory(address: Int, length: Int): ByteArray? = synchronized(vmLock) {
        if (!active || address < 0 || length < 0 || address.toLong() + length > RAM_BYTES) return null
        ram.copyOfRange(address, address + length)
    }

    /** Writes [value] as [size] (1, 2 or 4) bytes at [address] of main RAM. Blocks. */
    @JvmStatic fun writeMemory(address: Int, size: Int, value: Int): Boolean = synchronized(vmLock) {
        if (!active || address < 0 || address.toLong() + size > RAM_BYTES) return false
        writeRam(address, size, value)
        true
    }

    private fun writeRam(address: Int, size: Int, value: Int) {
        if (address < 0 || address + size > RAM_BYTES) return
        for (i in 0 until size) ram[address + i] = (value ushr (8 * i)).toByte()
    }

    // ---- Info ----

    @JvmStatic fun getFps(): Float = fps
    @JvmStatic fun getFrameNumber(): Long = frame

    /** The disc's serial (e.g. "SLUS-00067"), or null if the core cannot read one from [path]. */
    @JvmStatic fun getGameSerial(path: String): String? = serialOf(path.substringAfterLast('/'))

    /** A made-up serial from the file name, stable across launches. */
    private fun serialOf(name: String): String = "STUB-%08X".format(name.hashCode())

    /**
     * The index of the disc in the drive (as a string), then the titles of the discs of the loaded
     * playlist (.m3u); null when the loaded image is not a playlist. Blocks.
     */
    @JvmStatic fun getDiscPlaylist(): Array<String>? = playlist?.let { arrayOf(disc.toString()) + it }

    /** Puts disc [index] of the loaded playlist in the drive. Blocks. */
    @JvmStatic fun switchDisc(index: Int): Boolean {
        val list = playlist ?: return false
        if (index !in list.indices) return false
        disc = index
        discLabel = list[index]
        return true
    }

    /** Puts the disc image at [path] in the drive. Blocks. */
    @JvmStatic fun insertDisc(path: String): Boolean {
        if (!active) return false
        discLabel = path.substringAfterLast('/')
        return true
    }

    /** The disc set's name, then the serials of its discs in disc order, for [serial]'s game in the GameDB; null when it lists no set. */
    @JvmStatic fun getDiscSet(serial: String): Array<String>? = null

    /** The GameDB's title for [serial], or null. */
    @JvmStatic fun getGameDbTitle(serial: String): String? = null

    /**
     * "<region>\t<description>" for a BIOS image, or null. The region is 1 NTSC-J, 2 NTSC-U,
     * 3 PAL, or -1 for an image the core does not know. Takes ownership of [fd].
     *
     * The stub takes any file as a BIOS it does not know.
     */
    @JvmStatic fun getBiosInfoFromFd(fd: Int): String? {
        ParcelFileDescriptor.adoptFd(fd).close()
        return "-1\tStub BIOS"
    }

    /** The entries of the playlist at [path], by file name without the extension. */
    private fun readPlaylist(path: String): List<String>? {
        val fd = files?.open(path)?.takeIf { it >= 0 } ?: return null
        return ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.adoptFd(fd)).bufferedReader().useLines { lines ->
            lines.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
                .map { it.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.') }.toList()
        }.takeIf { it.isNotEmpty() }
    }

    // ---- Drawing ----

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun drawToSurface() = surfaceLock.withLock {
        val s = surface?.takeIf { it.isValid } ?: return
        val canvas = runCatching { s.lockHardwareCanvas() }.getOrNull() ?: return
        try {
            draw(canvas, canvas.width, canvas.height)
        } finally {
            s.unlockCanvasAndPost(canvas)
        }
    }

    private fun render(width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { draw(Canvas(it), width, height) }

    private fun png(bitmap: Bitmap): ByteArray? = runCatching {
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }.getOrNull().also { bitmap.recycle() }

    /** A 4:3 test picture: a moving hue, the game, the frame and what the first pad holds. */
    private fun draw(canvas: Canvas, width: Int, height: Int) {
        canvas.drawColor(Color.BLACK)
        val w = minOf(width.toFloat(), height * 4f / 3f)
        val h = w * 3f / 4f
        val left = (width - w) / 2f
        val top = (height - h) / 2f
        paint.color = Color.HSVToColor(floatArrayOf((frame % 360).toFloat(), 0.5f, 0.35f))
        canvas.drawRect(left, top, left + w, top + h, paint)

        paint.color = Color.WHITE
        paint.textSize = h / 16f
        val pad = binds[0]
        val held = PadButton.entries.filter { pad[it.code] > 0.5f }.joinToString(" ") { it.name }
        val stick = sticks[0]
        val lines = listOf(
            "Stub core",
            gameName,
            "Disc: $discLabel",
            "Frame $frame",
            "Held: ${held.ifEmpty { "-" }}",
            "L (%.2f, %.2f)  R (%.2f, %.2f)".format(stick[0], stick[1], stick[2], stick[3]),
        )
        lines.forEachIndexed { i, line -> canvas.drawText(line, left + h / 16f, top + h / 8f * (i + 1), paint) }

        // The left stick as a dot.
        val cx = left + w * 0.8f
        val cy = top + h * 0.75f
        val r = h / 10f
        paint.style = Paint.Style.STROKE
        canvas.drawCircle(cx, cy, r, paint)
        paint.style = Paint.Style.FILL
        canvas.drawCircle(cx + stick[0] * r, cy + stick[1] * r, r / 5f, paint)
    }

    // ---- Called from native ----
    // With a native core, these run on core threads; nothing may throw out of them.

    @JvmStatic
    fun onVmPausedChanged(paused: Boolean) {
        runCatching { listener?.onVmPausedChanged(paused) }
            .onFailure { Log.e(TAG, "onVmPausedChanged", it) }
    }

    @JvmStatic
    fun safOpen(path: String): Int = runCatching { files?.open(path) }
        .onFailure { Log.e(TAG, "safOpen", it) }.getOrNull() ?: -1

    @JvmStatic
    fun safStat(path: String): LongArray? = runCatching { files?.stat(path) }
        .onFailure { Log.e(TAG, "safStat", it) }.getOrNull()

    @JvmStatic
    fun safList(path: String): Array<String>? = runCatching { files?.list(path) }
        .onFailure { Log.e(TAG, "safList", it) }.getOrNull()

    /** The game set [pad]'s motors (0..255 each); zeros when it stops them or the VM pauses. */
    @JvmStatic
    fun onPadRumble(pad: Int, largeMotor: Int, smallMotor: Int) {
        runCatching { listener?.onPadRumble(pad, largeMotor, smallMotor) }
            .onFailure { Log.e(TAG, "onPadRumble", it) }
    }
}
