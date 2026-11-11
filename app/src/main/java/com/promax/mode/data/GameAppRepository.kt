package com.promax.mode.data

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build

data class LaunchableApp(
    val packageName: String,
    val label: String,
    val isLikelyGame: Boolean
)

object GameAppRepository {
    private const val PREFS = "promax_settings"
    private const val KEY_GAMES = "game_packages"

    fun launchableApps(context: Context): List<LaunchableApp> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val results = if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }

        return results.mapNotNull { info ->
            val activityInfo = info.activityInfo ?: return@mapNotNull null
            val appInfo = activityInfo.applicationInfo
            val label = info.loadLabel(pm)?.toString()?.takeIf { it.isNotBlank() }
                ?: activityInfo.packageName
            val category = if (Build.VERSION.SDK_INT >= 26) appInfo.category else ApplicationInfo.CATEGORY_UNDEFINED
            val nameHint = label.lowercase()
            val gameHint = nameHint.contains("game") ||
                nameHint.contains("craft") ||
                nameHint.contains("pubg") ||
                nameHint.contains("ludo") ||
                nameHint.contains("free fire")
            LaunchableApp(
                packageName = activityInfo.packageName,
                label = label,
                isLikelyGame = category == ApplicationInfo.CATEGORY_GAME || gameHint
            )
        }.distinctBy { it.packageName }
            .sortedWith(compareByDescending<LaunchableApp> { it.isLikelyGame }.thenBy { it.label.lowercase() })
    }

    fun savedPackages(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_GAMES, emptySet())
            ?.toSet()
            ?: emptySet()

    fun savePackages(context: Context, packages: Set<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_GAMES, packages)
            .apply()
    }

    fun labelFor(context: Context, packageName: String): String {
        return try {
            context.packageManager.getApplicationLabel(
                context.packageManager.getApplicationInfo(packageName, 0)
            ).toString()
        } catch (_: Exception) {
            packageName
        }
    }
}
