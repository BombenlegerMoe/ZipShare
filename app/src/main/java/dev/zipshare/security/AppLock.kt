package dev.zipshare.security

import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dev.zipshare.data.prefs.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Times are [SystemClock.elapsedRealtime]: the wall clock can be set back by whoever holds the
 * phone, which made the time away negative and skipped the lock.
 */
internal fun shouldLock(enabled: Boolean, backgroundedAt: Long?, now: Long, timeoutSeconds: Int) =
    enabled && backgroundedAt != null && now - backgroundedAt >= timeoutSeconds * 1000L

/** Locked on cold start and after the configured background timeout. */
@Singleton
class AppLock @Inject constructor(private val settings: SettingsStore) : DefaultLifecycleObserver {

    private val scope = CoroutineScope(SupervisorJob())
    private val _locked = MutableStateFlow(true)
    val locked: StateFlow<Boolean> = _locked

    private var backgroundedAt: Long? = null
    private var started = false

    // Cached so onStart decides before the first frame instead of after a settings read.
    @Volatile private var enabled = true
    @Volatile private var timeoutSeconds = 0

    fun start() {
        if (started) return
        started = true
        scope.launch {
            settings.settings.collect {
                enabled = it.appLockEnabled
                timeoutSeconds = it.lockTimeoutSeconds
                if (!it.appLockEnabled) _locked.value = false
            }
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    fun unlock() {
        _locked.value = false
    }

    override fun onStop(owner: LifecycleOwner) {
        backgroundedAt = SystemClock.elapsedRealtime()
    }

    override fun onStart(owner: LifecycleOwner) {
        if (shouldLock(enabled, backgroundedAt, SystemClock.elapsedRealtime(), timeoutSeconds)) {
            _locked.value = true
        }
    }
}
