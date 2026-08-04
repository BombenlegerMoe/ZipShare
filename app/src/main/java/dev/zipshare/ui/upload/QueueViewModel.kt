package dev.zipshare.ui.upload

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.zipshare.data.ProfileRepository
import dev.zipshare.data.model.Profile
import dev.zipshare.upload.UploadEnqueuer
import dev.zipshare.upload.UploadWorker
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.util.UUID
import javax.inject.Inject

/** One line on the queue screen. */
data class QueueRow(
    val id: UUID,
    val name: String,
    val state: WorkInfo.State,
    /** Only meaningful while running. */
    val percent: Int,
    val error: String?,
) {
    val running: Boolean get() = state == WorkInfo.State.RUNNING
    val failed: Boolean get() = state == WorkInfo.State.FAILED
}

/**
 * The queue is WorkManager's, not a second one kept alongside it.
 *
 * Uploads already run as tagged unique work with progress and retry handled there, so a Room-backed
 * mirror would be a copy of that state that can only drift. What this costs is ordering: WorkInfo
 * carries no timestamp and a chain does not expose its position, so rows group by state instead.
 */
@HiltViewModel
class QueueViewModel @Inject constructor(
    private val workManager: WorkManager,
    private val profileRepo: ProfileRepository,
) : ViewModel() {

    val profiles: StateFlow<List<Profile>> = profileRepo.profiles
    val active: StateFlow<Profile?> = profileRepo.active
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun selectProfile(id: String) = profileRepo.setActive(id)

    val rows: StateFlow<List<QueueRow>> = workManager
        .getWorkInfosByTagFlow(UploadEnqueuer.TAG)
        .map { infos -> infos.filter { it.state in SHOWN }.map { it.toRow() }.sortedBy { ORDER.indexOf(it.state) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun cancel(id: UUID) {
        workManager.cancelWorkById(id)
    }

    fun cancelAll() {
        workManager.cancelAllWorkByTag(UploadEnqueuer.TAG)
    }

    /** Drops finished work from WorkManager's own database, which is what clears failed rows. */
    fun clearFinished() {
        workManager.pruneWork()
    }

    private fun WorkInfo.toRow() = QueueRow(
        id = id,
        // Progress wins while running (the worker may have resolved a better name than the
        // enqueuer did), then the output of a finished attempt, then the tag put on at enqueue.
        name = progress.getString(UploadWorker.KEY_OUT_NAME)
            ?: outputData.getString(UploadWorker.KEY_OUT_NAME)
            ?: tags.firstOrNull { it.startsWith(UploadEnqueuer.NAME_TAG) }
                ?.removePrefix(UploadEnqueuer.NAME_TAG)
            ?: "File",
        state = state,
        percent = progress.getInt(UploadWorker.KEY_PROGRESS, 0),
        error = outputData.getString(UploadWorker.KEY_ERROR),
    )

    private companion object {
        /**
         * Succeeded uploads are already on Home under "On this device", and cancelled ones are
         * gone on purpose - repeating either here would just be a second list to dismiss.
         */
        val SHOWN = setOf(
            WorkInfo.State.RUNNING,
            WorkInfo.State.ENQUEUED,
            WorkInfo.State.BLOCKED,
            WorkInfo.State.FAILED,
        )
        val ORDER = listOf(
            WorkInfo.State.RUNNING,
            WorkInfo.State.ENQUEUED,
            WorkInfo.State.BLOCKED,
            WorkInfo.State.FAILED,
        )
    }
}
