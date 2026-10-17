// Author: Pandiyaraj Karuppasamy
// Date: Oct-17-2026

package com.pandiyarajk.sportshub.alerts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * Fires at a followed match's start time, and as the watchdog when the service has died. An exact alarm is one of the few moments Android lets
 * an app start a foreground service from the background, so the service is started here and not
 * from the worker.
 */
class AlertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            ContextCompat.startForegroundService(context, Intent(context, LiveAlertService::class.java))
        } catch (e: IllegalStateException) {
            // An inexact alarm (no exact alarm access) is not exempt: ask for a tap instead.
            AlertNotifier.postTapToFollow(context)
        } catch (e: SecurityException) {
            AlertNotifier.postTapToFollow(context)
        }
    }
}
