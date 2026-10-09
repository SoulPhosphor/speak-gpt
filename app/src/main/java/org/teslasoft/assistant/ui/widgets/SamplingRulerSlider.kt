package org.teslasoft.assistant.ui.widgets

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import com.google.android.material.color.MaterialColors
import com.google.android.material.slider.Slider
import org.teslasoft.assistant.R

/** Readable visual guides independent of the parameter's fine numeric step. */
class SamplingRulerSlider @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.sliderStyle
) : Slider(context, attrs, defStyleAttr) {
    private val rulerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MaterialColors.getColor(this@SamplingRulerSlider,
            com.google.android.material.R.attr.colorOnSurfaceVariant)
        strokeWidth = resources.getDimension(R.dimen.sampling_slider_tick_width)
    }
    private val minorHeight = resources.getDimension(R.dimen.sampling_slider_tick_height)
    private val majorHeight = resources.getDimension(R.dimen.sampling_slider_major_tick_height)
    private val tickGap = resources.getDimension(R.dimen.sampling_slider_tick_gap)
    private val intervals = resources.getInteger(R.integer.sampling_slider_ruler_intervals)
    private val majorInterval = resources.getInteger(R.integer.sampling_slider_ruler_major_interval)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // This shared style hides labels and uses wrap_content height, so the
        // track is centered. Read its real horizontal inset and width from
        // Material instead of stretching marks across the whole view.
        val top = height / 2f + trackHeight / 2f + tickGap
        for (index in 0..intervals) {
            val x = trackSidePadding + trackWidth * index.toFloat() / intervals
            val length = if (index % majorInterval == 0) majorHeight else minorHeight
            canvas.drawLine(x, top, x, top + length, rulerPaint)
        }
    }
}
