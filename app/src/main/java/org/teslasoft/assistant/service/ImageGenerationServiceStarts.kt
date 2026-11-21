package org.teslasoft.assistant.service

/** Reference counts and Android start callbacks are distinct. An instant completion may release
 * every request while startForegroundService callbacks are still queued. The service lifecycle
 * lock owns this state; stopping is safe only after those callbacks have promoted the service.
 */
internal class ImageGenerationServiceStarts {
    private var active = 0
    private var pending = 0
    val idle: Boolean get() = active == 0 && pending == 0

    fun requested() { active++; pending++ }
    fun refused() { active = (active - 1).coerceAtLeast(0); pending = (pending - 1).coerceAtLeast(0) }
    fun delivered() { pending = (pending - 1).coerceAtLeast(0) }
    fun released() { active = (active - 1).coerceAtLeast(0) }
}
