// Author: Pandiyaraj Karuppasamy
// Date: Oct-17-2026

package com.pandiyarajk.sportshub.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.pandiyarajk.sportshub.alerts.AlertNotifier

/**
 * Whether alerts can actually be shown, and the ways to fix it. Notification permission exists
 * only from Android 13; below that, "granted" means the user has not switched the app's
 * notifications off, which only system settings can undo. Re-read on every resume, because the
 * user fixes it in a settings screen that reports nothing back.
 */
class NotificationGate(
    val granted: Boolean,
    private val ask: () -> Unit,
    val openSettings: () -> Unit,
) {
    /** In the same tap that turns an alert on: one gesture from intent to system dialog. */
    fun ensure() {
        if (!granted) ask()
    }
}

@Composable
fun rememberNotificationGate(onGranted: () -> Unit): NotificationGate {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(AlertNotifier.canPost(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val now = AlertNotifier.canPost(context)
        if (now && !granted) onGranted()
        granted = now
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = AlertNotifier.canPost(context)
        if (granted) onGranted()
    }
    val openSettings = { openNotificationSettings(context) }
    return NotificationGate(
        granted = granted,
        ask = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                openSettings()
            }
        },
        openSettings = openSettings,
    )
}

private fun openNotificationSettings(context: Context) {
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    } else {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
    }
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Battery optimisation exemption: special access, so it too is read on resume, never remembered. */
@Composable
fun rememberBatteryExempt(): Pair<Boolean, () -> Unit> {
    val context = LocalContext.current
    val power = remember { context.getSystemService(PowerManager::class.java) }
    var exempt by remember { mutableStateOf(power.isIgnoringBatteryOptimizations(context.packageName)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        exempt = power.isIgnoringBatteryOptimizations(context.packageName)
    }
    return exempt to {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
        runCatching { context.startActivity(intent) }
            .onFailure { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }
}

/** A bell beside a section title: lit when its alerts are on. */
@Composable
fun AlertBell(on: Boolean, label: String, onToggle: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = { onToggle(!on) }, modifier = modifier) {
        Icon(
            Icons.Filled.Notifications,
            contentDescription = if (on) "$label alerts on, tap to turn off" else "$label alerts off, tap to turn on",
            tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.alpha(if (on) 1f else 0.6f),
        )
    }
}
