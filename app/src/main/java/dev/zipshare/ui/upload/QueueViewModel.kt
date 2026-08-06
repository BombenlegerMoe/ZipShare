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
import kotlinx.coroutines.flow.combine
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

    /**
     * Scoped to the active server, because the top bar names one server and the list has to mean
     * that one. Every upload carries its profile as a tag, so the filter reads the same tag the
     * enqueuer wrote.
     */
    val rows: StateFlow<List<QueueRow>> = combine(
        workManager.getWorkInfosByTagFlow(UploadEnqueuer.TAG),
        active,
    ) { infos, profile ->
        infos.filter { it.state in ORDER && it.belongsTo(profile?.id) }
            .map { it.toRow() }
            .sortedBy { ORDER.indexOf(it.state) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Work enqueued before uploads carried a profile tag has no owner. It is shown under whichever
     * server is active rather than hidden: an upload nobody can see is also one nobody can cancel.
     */
    private fun WorkInfo.belongsTo(profileId: String?): Boolean =
        UploadEnqueuer.profileOf(tags)?.let { it == profileId } ?: true

    fun cancel(id: UUID) {
        workManager.cancelWorkById(id)
    }

    /**
     * Cancels what the list shows, by id, rather than everything wearing the upload tag - which
     * would have taken the other servers' uploads with it. Cancelling one member of a chain
     * cancels the files queued behind it, and a row already in a terminal state is a no-op.
     */
    fun cancelAll() {
        rows.value.forEach { workManager.cancelWorkById(it.id) }
    }

    /**
     * Leaving the screen drops the failed rows, which is what the "Clear failed" button used to do
     * by hand. Pruning only touches finished work, so anything still running or queued survives,
     * and the failure notification remains the record that outlives the screen.
     *
     * Not on entry: a failure that happened while another screen was open would be pruned before it
     * was ever shown. Not from a DisposableEffect either - that fires on rotation, which is not
     * leaving. The back stack entry (and so this ViewModel) dies only when the screen really goes.
     *
     * Unlike the list, this is not scoped to the active server: WorkManager prunes all finished
     * work or none, there being no prune-by-tag. So leaving the queue also drops another server's
     * failed rows before they were read. Their notifications still fired, which is the reason that
     * is tolerable - and the reason to fix it properly if a per-server record is ever wanted.
     */
    override fun onCleared() {
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
         * Both the filter and the sort order - one list, so a state cannot be added to one and
         * forgotten in the other (which would either hide the row or sort it above running uploads).
         *
         * Succeeded uploads are already on Home under "On this device", and cancelled ones are gone
         * on purpose - repeating either here would just be a second list to dismiss.
         */
        val ORDER = listOf(
            WorkInfo.State.RUNNING,
            WorkInfo.State.ENQUEUED,
            WorkInfo.State.BLOCKED,
            WorkInfo.State.FAILED,
        )
    }
}
