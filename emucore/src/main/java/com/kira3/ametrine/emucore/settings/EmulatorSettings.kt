package com.kira3.ametrine.emucore.settings

import android.view.KeyEvent
import kotlinx.serialization.Serializable

/**
 * Every emulator setting the app stores, global or per game. The core's settings are a chosen
 * part of MesenCE's (SnesConfig, VideoConfig, AudioConfig, EmulationConfig), set by the Android
 * host (android_host.cpp, ApplySettings) from the keys [CoreKeys] writes; the rest keep MesenCE's
 * defaults.
 *
 * Stored as JSON (see [SettingsJson]), nested by group: `{"display": {"scaling": "Sharp"}, ...}`.
 * Choice values are names ("Sharp", "NtscBlargg"), so they stay valid as options are added. Each
 * field's comment names the core key it maps to; fields marked "App side" never reach the core.
 */
@Serializable
data class EmulatorSettings(
    val console: ConsoleSettings = ConsoleSettings(),
    val display: DisplaySettings = DisplaySettings(),
    val frameLimit: FrameLimitSettings = FrameLimitSettings(),
    val audio: AudioSettings = AudioSettings(),
    val osd: OsdSettings = OsdSettings(),
    val output: OutputSettings = OutputSettings(),
    val rewind: RewindSettings = RewindSettings(),
    val controls: ControlsSettings = ControlsSettings(),
    val gemini: GeminiSettings = GeminiSettings(),
    val memoryEditor: MemoryEditorSettings = MemoryEditorSettings(),
)

/** The console itself. */
@Serializable
data class ConsoleSettings(
    /** Snes/Region: "Auto" (from the cartridge), "NTSC" or "PAL". Applies on the next boot. */
    val region: String = "Auto",
    /** Snes/RamPowerOnState: what the RAM holds at power on, "Random", "AllZeros" or "AllOnes". Applies on the next boot. */
    val ramPowerOnState: String = "Random",
    /** Snes/ExtraScanlinesBeforeNmi: extra lines each frame for the CPU, which removes slowdown (0 = off). */
    val overclockScanlines: Int = 0,
    /** Snes/GsuClockSpeed: the Super FX chip's clock as a percent (100 = the console's). */
    val superFxClockPercent: Int = 100,
)

/** How the picture is made and fitted to the screen. */
@Serializable
data class DisplaySettings(
    /** Video/AspectRatio: "Auto" (the console's 8:7 pixels), "NoStretching" (square pixels), "Standard" (4:3) or "Widescreen" (16:9). */
    val aspectRatio: String = "Auto",
    /** Video/Scaling: "Sharp" (sharp bilinear), "Nearest", "Smooth" (bilinear) or "Integer" (whole multiples, sharp). */
    val scaling: String = "Sharp",
    /** Video/Filter: "None", "NtscBlargg", "NtscBisqwit", "Scale2x", "HQ2x" or "xBRZ2x". */
    val filter: String = "None",
    /** Video/Scanlines: how dark the scanlines are, as a percent (0 = none). */
    val scanlines: Int = 0,
    /** Snes/HighResBlend: blends the 512-wide modes' pixel pairs, "None", "BlendAll" or "BlendEvenOdd". */
    val highResBlend: String = "None",
    /** Snes/OverscanTop: lines cut off the top (the console shows 224 of 239). */
    val overscanTop: Int = 7,
    /** Snes/OverscanBottom: lines cut off the bottom. */
    val overscanBottom: Int = 8,
)

/** Speed, and the buttons over the game. */
@Serializable
data class FrameLimitSettings(
    /** Emulation/SpeedPercent is 0 (unlimited) when off. */
    val frameLimitEnable: Boolean = true,
    /** Emulation/SpeedPercent: percent of full speed (10..500). */
    val nominalSpeedPercent: Int = 100,
    /** Emulation/FastForwardPercent as a multiple of full speed (2..10), or 0 for as fast as possible. */
    val fastForwardSpeed: Int = 0,
    /** Emulation/RunAheadFrames: frames run ahead to cut input lag (0 = off). Each one costs a frame's work. */
    val runAheadFrames: Int = 0,
    /** Show the fast-forward button over the game. App side. */
    val fastForwardButton: Boolean = true,
    /** Show a screenshot button over the game. Off by default. App side. */
    val screenshotButton: Boolean = false,
    /** Show a macro button over the game. Off by default. App side. */
    val macroButton: Boolean = false,
)

/** Audio output (AAudio). */
@Serializable
data class AudioSettings(
    /** Audio/Volume: 0..100 percent. */
    val volume: Int = 100,
    /** Audio/FastForwardVolume: 0..100 percent of [volume] while fast-forwarding. */
    val fastForwardVolume: Int = 100,
    /** Audio/Muted. */
    val muted: Boolean = false,
    /** Audio/LatencyMs: how much sound is buffered ahead. Lower is snappier, higher crackles less. */
    val latencyMs: Int = 60,
    /** Snes/Interpolation: the sound chip's sample interpolation, "Gauss" (the console's), "Cubic", "Sinc" or "None". */
    val interpolation: String = "Gauss",
)

/** What is drawn over the game. App side. */
@Serializable
data class OsdSettings(
    /** The frames per second, in a corner. */
    val showFps: Boolean = false,
)

/** The play screen. App side. */
@Serializable
data class OutputSettings(
    /** Output surface size: 0 = screen, else n x 448 lines on the short side. */
    val hwScaler: Int = 0,
    /** "auto", "2560x1440", "1920x1080" or "1280x720". */
    val screenResOverride: String = "auto",
    /** Play screen orientation: 0 device setting, 1 landscape, 2 portrait, 3 auto-rotate. */
    val orientation: Int = 0,
    /** In portrait, align the picture to the top (true) instead of centering it. */
    val portraitRenderTop: Boolean = true,
)

/** The on-screen pad and physical controllers. App side. */
@Serializable
data class ControlsSettings(
    /** How opaque the pad is, as a percent (20..100). */
    val padOpacity: Int = 100,
    /** Vibrate when a button is pressed. */
    val padHaptics: Boolean = true,
    /** Seconds without a touch after which the pad and the function buttons fade (1..30); above 30 they never do. */
    val padIdleSeconds: Int = 15,
    /** Stick dead zone of a physical controller (its left stick works as the D-pad), as a percent of the stick's travel. */
    val stickDeadzone: Int = 15,
    /** How far a physical controller's analog trigger must go to press L or R, as a percent. */
    val triggerThreshold: Int = 8,
    /** Which key of a physical controller presses each pad button. */
    val buttonMap: ButtonMapSettings = ButtonMapSettings(),
)

/**
 * The Android key code (KeyEvent.KEYCODE_*) that presses each pad button; 0 leaves the button
 * without a key. The D-pad's HAT axis, the left stick and the analog triggers work whatever is
 * set here. The face buttons go by position: Android's A is the bottom button, the SNES pad's B.
 */
@Serializable
data class ButtonMapSettings(
    val up: Int = KeyEvent.KEYCODE_DPAD_UP,
    val down: Int = KeyEvent.KEYCODE_DPAD_DOWN,
    val left: Int = KeyEvent.KEYCODE_DPAD_LEFT,
    val right: Int = KeyEvent.KEYCODE_DPAD_RIGHT,
    val a: Int = KeyEvent.KEYCODE_BUTTON_B,
    val b: Int = KeyEvent.KEYCODE_BUTTON_A,
    val x: Int = KeyEvent.KEYCODE_BUTTON_Y,
    val y: Int = KeyEvent.KEYCODE_BUTTON_X,
    val l: Int = KeyEvent.KEYCODE_BUTTON_L1,
    val r: Int = KeyEvent.KEYCODE_BUTTON_R1,
    val start: Int = KeyEvent.KEYCODE_BUTTON_START,
    val select: Int = KeyEvent.KEYCODE_BUTTON_SELECT,
)

/** Rewind: points the core keeps in memory as the game runs, for the player to go back to. */
@Serializable
data class RewindSettings(
    /** Rewind/Enable. */
    val enabled: Boolean = true,
    /** Rewind/IntervalSeconds: seconds of play between points (1..60). */
    val intervalSeconds: Int = 5,
    /** Rewind/Keep: how many points to keep (1..20). */
    val keep: Int = 12,
)

/** Asking Gemini about the game screen. App side only: none of these reach the core. The API key is kept apart from settings. */
@Serializable
data class GeminiSettings(
    /** Show the AI button over the game. */
    val enabled: Boolean = false,
    /** Gemini model name, as the API takes it. */
    val model: String = "gemini-2.5-flash",
    /** Sent as the system instruction with every question. Empty sends none. */
    val systemPrompt: String = "",
    /** Show the saved memos over the game. */
    val memosVisible: Boolean = true,
)

/** Writing to the game's memory from the memory search. App side only: none of these reach the core. */
@Serializable
data class MemoryEditorSettings(
    /** Let the memory search change the values it lists. */
    val enabled: Boolean = false,
)

