package com.kira3.ametrine.emucore.settings

/** When a changed setting reaches a running game. Everything not listed applies right away. */
object SettingsEffects {
    /** Dotted paths of the settings that only take effect when a game next boots. */
    val appliesOnNextBoot: Set<String> = setOf(
        "console.region",
        "console.ramPowerOnState",
        "output.customDriverId",
        "output.turnipUbwcHint",
    )
}
