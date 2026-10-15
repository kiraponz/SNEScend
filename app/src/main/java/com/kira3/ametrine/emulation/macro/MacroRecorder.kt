package com.kira3.ametrine.emulation.macro

import com.kira3.ametrine.data.macro.Macro
import com.kira3.ametrine.data.macro.MacroEvent
import com.kira3.ametrine.data.macro.MacroWait
import com.kira3.ametrine.data.macro.MemoryCondition
import com.kira3.ametrine.emucore.EmulatorCore
import com.kira3.ametrine.emucore.PadButton

/**
 * Records what is sent to the pad of [port] while it runs, from the touch pad and a controller
 * alike. Time starts at the first input, so the wait before it is not kept. Time is counted in emulated frames.
 */
class MacroRecorder(private val core: EmulatorCore, private val port: Int) {
    private val events = ArrayList<MacroEvent>()
    private val waits = ArrayList<MacroWait>()
    private var firstAt = 0L
    private var lastFrame = 0L
    private var frame = 0L
    private val down = HashMap<PadButton, Float>()
    private var running = false

    /** Inputs recorded so far, for the on-screen counter. */
    val count: Int get() = synchronized(events) { events.size }

    private val listener = object : EmulatorCore.PadListener {
        override fun onButton(port: Int, button: PadButton, pressed: Boolean, pressure: Float) {
            if (port != this@MacroRecorder.port) return
            synchronized(events) {
                if (!pressed && !down.containsKey(button)) return
                if (pressed) down[button] = pressure else down.remove(button)
                add(MacroEvent(t = 0, button = button.name, pressed = pressed, value = pressure))
            }
        }
    }

    fun start() {
        synchronized(events) {
            events.clear()
            waits.clear()
            down.clear()
            firstAt = 0L
            lastFrame = core.frameCount
            frame = 0L
        }
        running = true
        core.padListener = listener
    }

    /** Puts a wait point at the current time: playback will hold here until [condition] holds (or [timeout] frames pass). */
    fun addWait(condition: MemoryCondition, timeout: Long?) {
        synchronized(events) { waits += MacroWait(elapsed(), condition, timeout) }
    }

    /**
     * Stops recording and returns the macro, or null if nothing was pressed. Whatever is still held
     * is let go at the end, and the last [TAIL_FRAMES] (the time spent reaching the stop button) are dropped.
     */
    fun stop(id: String, name: String): Macro? {
        if (!running) return null
        running = false
        core.padListener = null
        synchronized(events) {
            if (events.isEmpty()) return null
            val end = maxOf(events.last().t, waits.lastOrNull()?.t ?: 0L, elapsed() - TAIL_FRAMES)
            for (button in down.keys) events += MacroEvent(t = end, button = button.name, pressed = false)
            return Macro(id, name, end, events.toList(), frames = true, waits = waits.toList())
        }
    }

    fun cancel() {
        running = false
        core.padListener = null
    }

    private fun add(event: MacroEvent) {
        if (events.isEmpty()) firstAt = tick()
        events += event.copy(t = elapsed())
    }

    /** Frames since the first input (0 before it). */
    private fun elapsed(): Long = if (events.isEmpty()) 0L else tick() - firstAt

    /** The frames run since [start]. A load state sends the counter back; that adds nothing. */
    private fun tick(): Long {
        val now = core.frameCount
        if (now > lastFrame) frame += now - lastFrame
        lastFrame = now
        return frame
    }

    private companion object {
        const val TAIL_FRAMES = 24L
    }
}
