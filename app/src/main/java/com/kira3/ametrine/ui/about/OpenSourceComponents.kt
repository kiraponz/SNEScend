package com.kira3.ametrine.ui.about

import android.content.Context
import androidx.annotation.StringRes
import com.kira3.ametrine.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Something the APK contains that someone else wrote, with the license it is used under.
 *
 * [files] are under `assets/licenses/`. A core usually brings its own texts (Aegirine copies
 * them from the core's tree at build time); the app keeps the plain license texts for parts whose
 * tree has none. [notice] is the copyright line for those, shown above the text.
 */
data class OpenSourceComponent(
    val id: String,
    val name: String,
    val license: String,
    val url: String,
    val files: List<String>,
    val notice: String? = null,
)

enum class ComponentGroup(@param:StringRes val title: Int) {
    Core(R.string.licenses_group_core),
    Native(R.string.licenses_group_native),
    Assets(R.string.licenses_group_assets),
    Android(R.string.licenses_group_android),
}

/**
 * Everything the licenses screen lists, in the order it lists them. The stub core adds nothing; a
 * real core adds its own entry under [ComponentGroup.Core] and what it links under the others.
 */
val openSourceComponents: Map<ComponentGroup, List<OpenSourceComponent>> = mapOf(
    ComponentGroup.Android to listOf(
        OpenSourceComponent(
            "androidx", "AndroidX and Jetpack Compose", "Apache-2.0", "https://developer.android.com/jetpack/androidx",
            listOf("apache-2.0.txt"), notice = "Copyright The Android Open Source Project",
        ),
        OpenSourceComponent(
            "kotlin", "Kotlin, kotlinx.coroutines and kotlinx.serialization", "Apache-2.0", "https://kotlinlang.org",
            listOf("apache-2.0.txt"), notice = "Copyright JetBrains s.r.o. and Kotlin Programming Language contributors",
        ),
    ),
)

/** Where this app's source is published. */
const val SOURCE_URL = "https://github.com/kiraponz/SNEScend"

/** The core's source at [commit], kept so the core's source stays available. Null without a core repository. */
fun coreSourceUrl(commit: String): String? = null

/** This app itself, for its own license from About. */
val ametrineComponent = OpenSourceComponent(
    "snescend", "SNEScend", "GPL-3.0", SOURCE_URL, listOf("gpl-3.0.txt"),
    notice = "Copyright 2026 Kira",
)

fun openSourceComponent(id: String): OpenSourceComponent? =
    if (id == ametrineComponent.id) ametrineComponent
    else openSourceComponents.values.firstNotNullOfOrNull { group -> group.firstOrNull { it.id == id } }

/** The component's license texts, one after another. */
suspend fun licenseText(context: Context, component: OpenSourceComponent): String = withContext(Dispatchers.IO) {
    component.files.joinToString("\n\n") { name ->
        context.assets.open("licenses/$name").bufferedReader().use { it.readText() }.trimEnd()
    }
}
