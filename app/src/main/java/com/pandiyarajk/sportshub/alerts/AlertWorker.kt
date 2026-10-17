// Author: Pandiyaraj Karuppasamy
// Date: Oct-17-2026

package com.pandiyarajk.sportshub.alerts

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/** Every 30 minutes while alerts are on: catches matches that went live without an alarm. */
class AlertWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        AlertScheduler.sync(applicationContext)
        return Result.success()
    }
}
