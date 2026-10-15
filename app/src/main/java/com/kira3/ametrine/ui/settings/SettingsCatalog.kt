package com.kira3.ametrine.ui.settings

import android.view.KeyEvent
import com.kira3.ametrine.R
import com.kira3.ametrine.ui.settings.Label.Companion.res
import com.kira3.ametrine.ui.settings.Label.Companion.text

/**
 * What each settings category shows. Items are the core's settings (CoreKeys.kt) and the app's
 * own; option values are the stored values, names such as "Sharp" or "NtscBlargg".
 */
object SettingsCatalog {
    private val byCategory: Map<SettingsCategory, List<SettingsSection>> by lazy {
        mapOf(
            SettingsCategory.Graphics to graphics(),
            SettingsCategory.Performance to performance(),
            SettingsCategory.Audio to audio(),
            SettingsCategory.Controls to controls(),
            SettingsCategory.System to system(),
            SettingsCategory.Miscellaneous to miscellaneous(),
        )
    }

    fun sections(category: SettingsCategory): List<SettingsSection> = byCategory.getValue(category)

    private fun options(vararg labels: Label): List<Option> = labels.mapIndexed { index, label -> Option(index, label) }

    private val off = res(R.string.settings_opt_off)
    private val auto = res(R.string.settings_opt_auto)

    /** Options whose stored values are the core's names. */
    private fun named(vararg pairs: Pair<String, Label>): List<Option> = pairs.map { (value, label) -> Option(value, label) }

    private fun graphics() = listOf(
        SettingsSection(
            R.string.settings_section_picture,
            listOf(
                Choice(
                    "display.aspectRatio", R.string.settings_aspect_ratio, R.string.settings_aspect_ratio_summary,
                    named(
                        "Auto" to res(R.string.settings_opt_aspect_console),
                        "NoStretching" to res(R.string.settings_opt_par_1_1),
                        "Standard" to text("4:3"),
                        "Widescreen" to text("16:9"),
                    ),
                ),
                Choice(
                    "display.scaling", R.string.settings_display_filter, R.string.settings_display_filter_summary,
                    named(
                        "Sharp" to res(R.string.settings_opt_sharp),
                        "Nearest" to res(R.string.settings_opt_nearest),
                        "Smooth" to res(R.string.settings_opt_smooth),
                        "Integer" to res(R.string.settings_opt_nearest_integer),
                    ),
                ),
                Choice(
                    "output.orientation", R.string.settings_orientation, R.string.settings_orientation_summary,
                    options(
                        res(R.string.settings_opt_device), res(R.string.settings_opt_landscape),
                        res(R.string.settings_opt_portrait), res(R.string.settings_opt_auto_rotate),
                    ),
                    inline = true,
                ),
                Toggle("output.portraitRenderTop", R.string.settings_portrait_top),
            ),
        ),
        SettingsSection(
            R.string.settings_section_filters,
            listOf(
                Choice(
                    "display.filter", R.string.settings_video_filter, R.string.settings_video_filter_summary,
                    named(
                        "None" to off,
                        "NtscBlargg" to res(R.string.settings_opt_ntsc_blargg),
                        "NtscBisqwit" to res(R.string.settings_opt_ntsc_bisqwit),
                        "Scale2x" to text("Scale2x"),
                        "HQ2x" to text("HQ2x"),
                        "xBRZ2x" to text("xBRZ 2×"),
                    ),
                ),
                Slider("display.scanlines", R.string.settings_scanlines, range = 0..100, step = 5, unit = R.string.settings_unit_percent),
                Choice(
                    "display.highResBlend", R.string.settings_hires_blend, R.string.settings_hires_blend_summary,
                    named(
                        "None" to off,
                        "BlendAll" to res(R.string.settings_opt_blend_all),
                        "BlendEvenOdd" to res(R.string.settings_opt_blend_even_odd),
                    ),
                ),
            ),
        ),
        SettingsSection(
            R.string.settings_section_display,
            listOf(
                Slider("display.overscanTop", R.string.settings_overscan_top, R.string.settings_overscan_summary, range = 0..16, unit = R.string.settings_unit_lines),
                Slider("display.overscanBottom", R.string.settings_overscan_bottom, range = 0..16, unit = R.string.settings_unit_lines),
                Choice(
                    "output.hwScaler", R.string.settings_display_resolution, R.string.settings_display_resolution_summary,
                    listOf(
                        Option(0, res(R.string.settings_opt_screen)), Option(3, text("1440p")),
                        Option(2, text("960p")), Option(1, text("480p")),
                    ),
                    inline = true,
                ),
            ),
            advanced = true,
        ),
        SettingsSection(
            R.string.settings_section_overlay,
            listOf(
                Toggle("osd.showFps", R.string.settings_osd_fps),
                Toggle("frameLimit.screenshotButton", R.string.settings_screenshot_button, R.string.settings_screenshot_button_summary),
                Toggle("frameLimit.macroButton", R.string.settings_macro_button, R.string.settings_macro_button_summary),
            ),
            advanced = true,
        ),
    )

    private fun miscellaneous() = listOf(
        SettingsSection(
            R.string.settings_section_gemini,
            listOf(
                Toggle("gemini.enabled", R.string.settings_gemini_enabled, R.string.settings_gemini_enabled_summary),
                GeminiApiKeySetting,
                Toggle(
                    "gemini.memosVisible", R.string.settings_gemini_memos, R.string.settings_gemini_memos_summary,
                    enabledWhen = { it.boolean("gemini.enabled") },
                ),
                GeminiModelSetting,
                TextInput(
                    "gemini.systemPrompt", R.string.settings_gemini_prompt, R.string.settings_gemini_prompt_summary,
                    multiline = true, enabledWhen = { it.boolean("gemini.enabled") },
                ),
            ),
        ),
        SettingsSection(
            R.string.settings_section_memory_editor,
            listOf(
                Toggle(
                    "memoryEditor.enabled", R.string.settings_memory_editor, R.string.settings_memory_editor_summary,
                ),
            ),
        ),
    )

    private fun performance() = listOf(
        SettingsSection(
            R.string.settings_section_speed,
            listOf(
                Slider(
                    "frameLimit.nominalSpeedPercent", R.string.settings_speed_limit, R.string.settings_speed_limit_summary,
                    range = 10..500, step = 10, unit = R.string.settings_unit_percent,
                    enabledWhen = { it.boolean("frameLimit.frameLimitEnable") },
                ),
                Toggle("frameLimit.frameLimitEnable", R.string.settings_frame_limiter, R.string.settings_frame_limiter_summary),
                Choice(
                    "frameLimit.runAheadFrames", R.string.settings_run_ahead, R.string.settings_run_ahead_summary,
                    listOf(Option(0, off), Option(1, text("1")), Option(2, text("2")), Option(3, text("3"))),
                    inline = true,
                ),
            ),
        ),
        SettingsSection(
            R.string.settings_section_overclock,
            listOf(
                Slider(
                    "console.overclockScanlines", R.string.settings_overclock, R.string.settings_overclock_summary,
                    range = 0..500, step = 10, unit = R.string.settings_unit_lines, labels = mapOf(0 to off),
                ),
                Slider(
                    "console.superFxClockPercent", R.string.settings_superfx_clock, R.string.settings_superfx_clock_summary,
                    range = 100..800, step = 25, unit = R.string.settings_unit_percent,
                ),
            ),
            advanced = true,
        ),
    )

    private fun audio() = listOf(
        SettingsSection(
            R.string.settings_section_volume,
            listOf(
                Slider("audio.volume", R.string.settings_volume, range = 0..100, step = 5, unit = R.string.settings_unit_percent),
                Slider("audio.fastForwardVolume", R.string.settings_ff_volume, range = 0..100, step = 5, unit = R.string.settings_unit_percent),
                Toggle("audio.muted", R.string.settings_mute),
            ),
        ),
        SettingsSection(
            R.string.settings_section_audio_output,
            listOf(
                Slider(
                    "audio.latencyMs", R.string.settings_output_latency, R.string.settings_output_latency_summary,
                    range = 30..200, step = 10, unit = R.string.settings_unit_ms,
                ),
                Choice(
                    "audio.interpolation", R.string.settings_interpolation, R.string.settings_interpolation_summary,
                    named(
                        "Gauss" to res(R.string.settings_opt_gauss),
                        "Cubic" to res(R.string.settings_opt_cubic),
                        "Sinc" to res(R.string.settings_opt_sinc),
                        "None" to off,
                    ),
                    inline = true,
                ),
            ),
            advanced = true,
        ),
    )

    private val keyAssignments = listOf(
        KeyAssignSetting("controls.buttonMap.up", R.string.settings_button_up, KeyEvent.KEYCODE_DPAD_UP),
        KeyAssignSetting("controls.buttonMap.down", R.string.settings_button_down, KeyEvent.KEYCODE_DPAD_DOWN),
        KeyAssignSetting("controls.buttonMap.left", R.string.settings_button_left, KeyEvent.KEYCODE_DPAD_LEFT),
        KeyAssignSetting("controls.buttonMap.right", R.string.settings_button_right, KeyEvent.KEYCODE_DPAD_RIGHT),
        KeyAssignSetting("controls.buttonMap.a", R.string.settings_button_a, KeyEvent.KEYCODE_BUTTON_B),
        KeyAssignSetting("controls.buttonMap.b", R.string.settings_button_b, KeyEvent.KEYCODE_BUTTON_A),
        KeyAssignSetting("controls.buttonMap.x", R.string.settings_button_x, KeyEvent.KEYCODE_BUTTON_Y),
        KeyAssignSetting("controls.buttonMap.y", R.string.settings_button_y, KeyEvent.KEYCODE_BUTTON_X),
        KeyAssignSetting("controls.buttonMap.l", R.string.settings_button_l, KeyEvent.KEYCODE_BUTTON_L1),
        KeyAssignSetting("controls.buttonMap.r", R.string.settings_button_r, KeyEvent.KEYCODE_BUTTON_R1),
        KeyAssignSetting("controls.buttonMap.start", R.string.settings_button_start, KeyEvent.KEYCODE_BUTTON_START),
        KeyAssignSetting("controls.buttonMap.select", R.string.settings_button_select, KeyEvent.KEYCODE_BUTTON_SELECT),
    )

    private fun controls() = listOf(
        SettingsSection(
            R.string.settings_section_virtual_pad,
            listOf(
                Slider(
                    "controls.padOpacity", R.string.settings_pad_opacity,
                    range = 20..100, step = 5, unit = R.string.settings_unit_percent,
                ),
                Slider(
                    "controls.padIdleSeconds", R.string.settings_pad_idle, R.string.settings_pad_idle_summary,
                    range = 1..31, unit = R.string.settings_unit_seconds, labels = mapOf(31 to off),
                ),
                Toggle("controls.padHaptics", R.string.settings_pad_haptics),
                PadLayoutSetting,
            ),
        ),
        SettingsSection(
            R.string.settings_section_controllers,
            listOf(
                ControllersSetting,
                Slider(
                    "controls.stickDeadzone", R.string.settings_stick_deadzone,
                    range = 0..50, step = 1, unit = R.string.settings_unit_percent,
                ),
                Slider(
                    "controls.triggerThreshold", R.string.settings_trigger_threshold,
                    range = 1..90, step = 1, unit = R.string.settings_unit_percent,
                ),
                ResetSetting(
                    R.string.settings_stick_reset, R.string.settings_stick_reset_summary,
                    listOf("controls.stickDeadzone" to 15, "controls.triggerThreshold" to 8),
                ),
            ),
        ),
        SettingsSection(
            R.string.settings_section_button_map,
            listOf(
                *keyAssignments.toTypedArray(),
                ResetSetting(
                    R.string.settings_key_reset_all, R.string.settings_key_reset_all_summary,
                    keyAssignments.map { it.path!! to it.default },
                ),
            ),
        ),
    )

    private fun system() = listOf(
        SettingsSection(
            R.string.settings_section_boot,
            listOf(
                Choice(
                    "console.region", R.string.settings_region, R.string.settings_region_summary,
                    named("Auto" to auto, "NTSC" to text("NTSC (60 Hz)"), "PAL" to text("PAL (50 Hz)")),
                    inline = true,
                ),
                Choice(
                    "console.ramPowerOnState", R.string.settings_ram_power_on, R.string.settings_ram_power_on_summary,
                    named(
                        "Random" to res(R.string.settings_opt_ram_random),
                        "AllZeros" to res(R.string.settings_opt_ram_zeros),
                        "AllOnes" to res(R.string.settings_opt_ram_ones),
                    ),
                    inline = true,
                ),
                BiosManageSetting,
                MemoryCardsManageSetting,
            ),
        ),
        SettingsSection(
            R.string.settings_section_rewind,
            listOf(
                Toggle("rewind.enabled", R.string.settings_rewind, R.string.settings_rewind_summary),
                Slider(
                    "rewind.intervalSeconds", R.string.settings_rewind_interval,
                    range = 1..60, unit = R.string.settings_unit_seconds,
                    enabledWhen = { it.boolean("rewind.enabled") },
                ),
                Slider(
                    "rewind.keep", R.string.settings_rewind_keep, R.string.settings_rewind_keep_summary,
                    range = 1..20, enabledWhen = { it.boolean("rewind.enabled") },
                ),
            ),
        ),
    )
}
