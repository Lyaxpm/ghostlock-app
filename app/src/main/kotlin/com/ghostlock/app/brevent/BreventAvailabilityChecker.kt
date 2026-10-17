package com.ghostlock.app.brevent

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/**
 * Collects the static Brevent facts a check needs: whether the package is
 * installed, its version (the client must echo it in the protocol), and whether
 * the command entry point is resolvable. It never assumes that an installed
 * package implies a usable shell.
 */
class BreventAvailabilityChecker(private val context: Context) {
    fun checkPackage(): BreventAvailability {
        val resolver = context.packageManager
        val info = try {
            resolver.getPackageInfo(PACKAGE, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        } ?: return BreventAvailability(installed = false)

        val intent = Intent(ACTION_COMMAND).setPackage(PACKAGE)
        val resolvable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            resolver.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(0L)) != null
        } else {
            @Suppress("DEPRECATION")
            resolver.resolveActivity(intent, 0) != null
        }

        return BreventAvailability(
            installed = true,
            versionName = info.versionName,
            versionCode = info.longVersionCode,
            commandIntentResolvable = resolvable,
        )
    }

    companion object {
        const val PACKAGE = "me.piebridge.brevent"

        /** `BreventIntent.ACTION_COMMAND`; handled by BreventCmd (fire and forget). */
        const val ACTION_COMMAND = "me.piebridge.brevent.intent.action.COMMAND"
    }
}
