package org.teslasoft.assistant.ui.drawer

import android.app.Application
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
@ConscryptMode(ConscryptMode.Mode.OFF)
class LockedFoldersLayoutTest {
    private class Content(context: Context, private val desiredHeight: Int) : View(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), resolveSize(desiredHeight, heightMeasureSpec))
        }
    }

    @Test fun largeExpandedContainerLeavesHalfTheAreaForMainChatsAndKeepsItsHeadingVisible() {
        val context = RuntimeEnvironment.getApplication()
        val container = LockedFoldersLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val heading = Content(context, 80)
        val contents = Content(context, 2000)
        container.addView(heading, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 80))
        container.addView(contents, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        measure(container, 800)
        assertEquals(400, container.height)
        assertEquals(0, heading.top)
        assertEquals(80, heading.height)
        assertEquals(320, contents.height)
        // Recompute the cap when the available window becomes smaller.
        measure(container, 400)
        assertEquals(200, container.height)
        assertEquals(80, heading.height)
        assertEquals(120, contents.height)
    }

    @Test fun smallContainerUsesOnlyNeededSpaceAndShrinksToTheHeadingWhenCollapsed() {
        val context = RuntimeEnvironment.getApplication()
        val container = LockedFoldersLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val contents = Content(context, 100)
        container.addView(Content(context, 80), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 80))
        container.addView(contents, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        measure(container, 800)
        assertEquals(180, container.height)
        contents.visibility = View.GONE
        measure(container, 800)
        assertEquals(80, container.height)
    }

    private fun measure(view: View, availableHeight: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(availableHeight, View.MeasureSpec.AT_MOST))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }
}
