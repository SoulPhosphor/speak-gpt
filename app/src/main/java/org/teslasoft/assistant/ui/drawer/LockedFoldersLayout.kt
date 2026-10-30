package org.teslasoft.assistant.ui.drawer

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout

/** The locked layer can occupy at most half of the drawer's scrolling area.
 * The heading is measured first; the RecyclerView scrolls within what remains. */
class LockedFoldersLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = MeasureSpec.getSize(heightMeasureSpec)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(available / 2, MeasureSpec.AT_MOST))
    }
}
