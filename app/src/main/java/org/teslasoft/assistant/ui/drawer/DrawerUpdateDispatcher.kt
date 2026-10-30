package org.teslasoft.assistant.ui.drawer

/** Keeps list/layout updates off the drawer's opening and closing frames.
 * All calls are made on the UI thread. */
internal class DrawerUpdateDispatcher {
    private var moving = false
    private val pending = ArrayDeque<() -> Unit>()

    fun dispatch(update: () -> Unit) {
        if (moving) pending.addLast(update) else update()
    }

    fun setMoving(value: Boolean) {
        moving = value
        while (!moving && pending.isNotEmpty()) pending.removeFirst().invoke()
    }
}
