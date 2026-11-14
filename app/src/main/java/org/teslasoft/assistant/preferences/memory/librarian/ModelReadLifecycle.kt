package org.teslasoft.assistant.preferences.memory.librarian

/** Protects a native model from being closed during a read without making a
 * settings/UI caller wait for inference. The last reader releases a retired
 * model; new readers fail before accessing its native session. */
internal class ModelReadLifecycle(private val release: () -> Unit) {
    private val lock = Any()
    private var readers = 0
    private var retired = false
    private var released = false

    fun <T> read(work: () -> T): T {
        synchronized(lock) {
            check(!retired) { "Embedding model was removed or replaced" }
            readers++
        }
        try { return work() } finally {
            val shouldRelease = synchronized(lock) {
                readers--
                takeRelease()
            }
            if (shouldRelease) release()
        }
    }

    fun close() {
        val shouldRelease = synchronized(lock) {
            retired = true
            takeRelease()
        }
        if (shouldRelease) release()
    }

    /** Called only while holding [lock]. */
    private fun takeRelease(): Boolean {
        if (!retired || readers != 0 || released) return false
        released = true
        return true
    }
}
