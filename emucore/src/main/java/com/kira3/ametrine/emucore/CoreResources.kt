package com.kira3.ametrine.emucore

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * Puts the APK's `assets/resources` (GameDB, shaders, fonts) into `<dataDir>/resources`,
 * where the core reads them as plain files.
 *
 * The copy runs only when this install has not written them yet: a marker file records the
 * install that did, and is written after a clean pass, so a failed copy is retried at the
 * next launch.
 *
 * The same rule clears the GPU caches in `<dataDir>/cache`, which are regenerated on demand.
 * The core's own caches discard themselves on a build or driver mismatch; this is the second
 * line for anything an older build wrote before it stamped them.
 */
internal object CoreResources {
    private const val TAG = "CoreResources"
    private const val ASSET_ROOT = "resources"
    private const val CACHE_DIR = "cache"
    private const val MARKER = ".install"

    fun install(context: Context, dataDir: File) {
        clearGpuCache(context, File(dataDir, CACHE_DIR))
        installResources(context, dataDir)
    }

    /**
     * Drops the GPU caches on the first launch of a new install. The marker sits beside them, so
     * a data folder that moved is cleared where it now is, and it is written only after a clean
     * wipe, so a failed one is retried at the next launch.
     */
    private fun clearGpuCache(context: Context, cacheDir: File) {
        val marker = File(cacheDir, MARKER)
        val install = installId(context)
        if (runCatching { marker.readText() }.getOrNull() == install) return

        val wiped = !cacheDir.exists() || cacheDir.deleteRecursively()
        if (wiped && cacheDir.mkdirs()) {
            marker.writeText(install)
            Log.i(TAG, "New install: cleared the GPU cache")
        } else {
            Log.w(TAG, "New install: could not clear ${cacheDir.path}; will retry next launch")
        }
    }

    private fun installResources(context: Context, dataDir: File) {
        val target = File(dataDir, ASSET_ROOT)
        val marker = File(target, MARKER)
        val install = installId(context)
        if (runCatching { marker.readText() }.getOrNull() == install) return

        // Start clean so files dropped from a newer build don't linger.
        target.deleteRecursively()
        // A core without resources (the stub) ships none.
        if (context.assets.list(ASSET_ROOT).isNullOrEmpty()) {
            marker.parentFile?.mkdirs()
            marker.writeText(install)
            return
        }
        val ok = copyTree(context.assets, ASSET_ROOT, target)
        if (ok) {
            marker.writeText(install)
        } else {
            Log.e(TAG, "Copying core resources failed; will retry next launch")
        }
    }

    /** Changes on every install, including a rebuilt APK with the same versionCode. */
    private fun installId(context: Context): String {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return "${context.packageName} ${info.longVersionCode} ${info.lastUpdateTime}"
    }

    private fun copyTree(assets: AssetManager, path: String, dest: File): Boolean {
        return try {
            val children = assets.list(path).orEmpty()
            if (children.isEmpty()) {
                // A file (asset folders are never empty).
                dest.parentFile?.mkdirs()
                assets.open(path).use { input -> dest.outputStream().use { input.copyTo(it) } }
                true
            } else {
                dest.mkdirs()
                children.fold(true) { ok, child ->
                    copyTree(assets, "$path/$child", File(dest, child)) && ok
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to copy asset $path", e)
            false
        }
    }
}
