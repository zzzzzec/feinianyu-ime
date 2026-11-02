// SPDX-License-Identifier: GPL-3.0-or-later
package org.fcitx.fcitx5.android.dataset

import android.app.job.JobParameters
import android.app.job.JobService
import kotlinx.coroutines.*

class TelemetryJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableMapOf<Int, Job>()
    override fun onStartJob(params: JobParameters): Boolean {
        jobs[params.jobId] = scope.launch {
            val retry = TelemetrySync.sync(this@TelemetryJobService)
            withContext(Dispatchers.Main) { jobs.remove(params.jobId); jobFinished(params, retry) }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { jobs.remove(params.jobId)?.cancel(); return true }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
