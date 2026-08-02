package dev.zipshare.security

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

/** Locked on cold start and after the configured background timeout. */
@Singleton
class AppLock @Inject constructor(private val settings: SettingsStore) : DefaultLifecycleObserver {

    private val scope = CoroutineScope(SupervisorJob())
    private val _locked = MutableStateFlow(true)
    val locked: StateFlow<Boolean> = _locked

    private var backgroundedAt = 0L
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch { _locked.value = settings.current().appLockEnabled }
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    fun unlock() {
        _locked.value = false
    }

    override fun onStop(owner: LifecycleOwner) {
        backgroundedAt = System.currentTimeMillis()
    }

    override fun onStart(owner: LifecycleOwner) {
        scope.launch {
            val cfg = settings.current()
            if (!cfg.appLockEnabled) {
                _locked.value = false
                return@launch
            }
            if (backgroundedAt == 0L) return@launch
            val awaySeconds = (System.currentTimeMillis() - backgroundedAt) / 1000
            if (awaySeconds >= cfg.lockTimeoutSeconds) _locked.value = true
        }
    }
}
