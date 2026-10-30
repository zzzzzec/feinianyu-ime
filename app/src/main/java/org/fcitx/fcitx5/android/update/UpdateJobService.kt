// SPDX-License-Identifier: GPL-3.0-or-later
package org.fcitx.fcitx5.android.update

import android.app.job.JobParameters
import android.app.job.JobService
import kotlinx.coroutines.*

class UpdateJobService : JobService() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null
    override fun onStartJob(params: JobParameters): Boolean {
        job = scope.launch { try { AppUpdater.check(this@UpdateJobService) } finally { jobFinished(params, false) } }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { job?.cancel(); return true }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
