package dev.zipshare.debug

/** Release no-op counterpart; StrictMode is never armed in a shipping build. */
object StrictModeSetup {
    fun install() = Unit
}
