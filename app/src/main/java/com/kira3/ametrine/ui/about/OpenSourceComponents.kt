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

/** The sources of the MesenCE submodule (external/MesenCE). */
private const val MESEN_URL = "https://github.com/nesdev-org/MesenCE"

/** Third-party code inside MesenCE's tree that the core is built with (emucore/src/main/cpp/CMakeLists.txt). */
private fun mesenPart(id: String, name: String, license: String, files: List<String>, notice: String) =
    OpenSourceComponent(id, name, license, MESEN_URL, files, notice)

/**
 * Everything the licenses screen lists, in the order it lists them: MesenCE, the code it carries
 * that the core is built from (all of Core/, Utilities/, SevenZip/ and Lua/), and the app's libraries.
 */
val openSourceComponents: Map<ComponentGroup, List<OpenSourceComponent>> = mapOf(
    ComponentGroup.Core to listOf(
        OpenSourceComponent(
            "mesence", "MesenCE", "GPL-3.0", MESEN_URL, listOf("gpl-3.0.txt"),
            notice = "Copyright (C) 2014-2025 Sour, 2026 contributors",
        ),
    ),
    ComponentGroup.Native to listOf(
        mesenPart("blip_buf", "blip_buf", "LGPL-2.1-or-later", listOf("lgpl-2.1-or-later.txt"), "Copyright (C) 2003-2009 Shay Green"),
        mesenPart("snes_ntsc", "snes_ntsc and nes_ntsc", "LGPL-2.1-or-later", listOf("lgpl-2.1-or-later.txt"), "Copyright (C) 2006-2007 Shay Green"),
        mesenPart("hqx", "hqx", "LGPL-2.1-or-later", listOf("lgpl-2.1-or-later.txt"), "Copyright (C) 2003 Maxim Stepin, (C) 2010 Cameron Zemek"),
        mesenPart("xbrz", "xBRZ", "GPL-3.0", listOf("gpl-3.0.txt"), "Copyright (C) Zenju"),
        mesenPart("scale2x", "Scale2x", "GPL-2.0-or-later", listOf("gpl-2.0-or-later.txt"), "Copyright (C) 2003 Andrea Mazzoleni"),
        mesenPart("saieagle", "2xSaI, Super 2xSaI and Super Eagle", "GPL-3.0", listOf("gpl-3.0.txt"), "Copyright (C) 2010-2014 Hans-Kristian Arntzen, (C) 2011-2014 Daniel De Matteis"),
        mesenPart("ymfm", "ymfm", "BSD-3-Clause", listOf("bsd-3-clause.txt"), "Copyright (c) 2021, Aaron Giles"),
        mesenPart("kissfft", "KISS FFT", "BSD-3-Clause", listOf("bsd-3-clause.txt"), "Copyright (c) 2003-2010, Mark Borgerding"),
        mesenPart("spng", "libspng", "BSD-2-Clause AND libpng-2.0", listOf("bsd-2-clause.txt", "libpng-2.0.txt"), "libspng authors; parts copyright (c) 1995-2019 The PNG Reference Library Authors"),
        mesenPart("magic_enum", "magic_enum", "MIT", listOf("mit.txt"), "Copyright (c) 2019 - 2022 Daniil Goncharov"),
        mesenPart("lua", "Lua", "MIT", listOf("mit.txt"), "Copyright (C) 1994-2025 Lua.org, PUC-Rio"),
        mesenPart("luasocket", "LuaSocket", "MIT", listOf("mit.txt"), "Copyright (C) 1999-2013 Diego Nehab"),
        mesenPart("miniz", "miniz", "Unlicense", listOf("unlicense.txt"), "Rich Geldreich"),
        mesenPart("lzma", "LZMA SDK (7z)", "Public domain", listOf("public-domain.txt"), "Igor Pavlov"),
        mesenPart("stb_vorbis", "stb_vorbis", "Public domain", listOf("public-domain.txt"), "Sean Barrett"),
        mesenPart("sha1", "SHA-1", "Public domain", listOf("public-domain.txt"), "Steve Reid, Bruce Guenter, Volker Grabsch"),
    ),
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

/** The core's source at [commit], kept so the core's source stays available. Null when the commit is unknown. */
fun coreSourceUrl(commit: String): String? = commit.takeIf { it.length == 40 }?.let { "$MESEN_URL/tree/$it" }

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
