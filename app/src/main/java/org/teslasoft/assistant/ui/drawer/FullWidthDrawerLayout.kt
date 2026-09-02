package org.teslasoft.assistant.ui.drawer

import android.content.Context
import android.view.Gravity
import android.view.View
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout

/**
 * DrawerLayout normally subtracts its navigation-drawer safety margin from a
 * MATCH_PARENT drawer. This screen deliberately uses a full-screen drawer, so
 * give the drawer child an explicit measured width before the parent applies
 * that default margin.
 */
class FullWidthDrawerLayout(context: Context) : DrawerLayout(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = View.MeasureSpec.getSize(widthMeasureSpec)
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            val params = child.layoutParams as? DrawerLayout.LayoutParams ?: continue
            val absoluteGravity = GravityCompat.getAbsoluteGravity(params.gravity, layoutDirection)
            if (absoluteGravity and Gravity.HORIZONTAL_GRAVITY_MASK != 0 && params.width != width) {
                params.width = width
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
