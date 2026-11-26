package com.stag200.gofast

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

class OscilloscopeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val maxPoints = 50
    private val petrolSeries = ArrayDeque<Float>()
    private val gasSeries = ArrayDeque<Float>()
    private val pressSeries = ArrayDeque<Float>()

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1E293B")
        strokeWidth = 2f
    }
    private val petrolPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FBBF24")
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }
    private val gasPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#38BDF8")
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }
    private val pressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#34D399")
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#94A3B8")
        textSize = 26f
    }

    fun addSample(petrolMs: Double, gasMs: Double, gasBar: Double) {
        petrolSeries.addLast(petrolMs.toFloat())
        gasSeries.addLast(gasMs.toFloat())
        pressSeries.addLast((gasBar * 10.0).toFloat()) // scaled x10 to fit 0..20 range
        while (petrolSeries.size > maxPoints) petrolSeries.removeFirst()
        while (gasSeries.size > maxPoints) gasSeries.removeFirst()
        while (pressSeries.size > maxPoints) pressSeries.removeFirst()
        invalidate()
    }

    fun clear() {
        petrolSeries.clear()
        gasSeries.clear()
        pressSeries.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        // Horizontal grid lines (0, 5, 10, 15, 20)
        val maxY = 20f
        for (i in 0..4) {
            val y = h - (i / 4f) * (h - 24f) - 12f
            canvas.drawLine(0f, y, w, y, gridPaint)
        }

        drawSeries(canvas, pressSeries, pressPaint, w, h, maxY)
        drawSeries(canvas, petrolSeries, petrolPaint, w, h, maxY)
        drawSeries(canvas, gasSeries, gasPaint, w, h, maxY)

        canvas.drawText("Бенз (мс)", 14f, 30f, labelPaint.apply { color = Color.parseColor("#FBBF24") })
        canvas.drawText("Газ (мс)", 170f, 30f, labelPaint.apply { color = Color.parseColor("#38BDF8") })
        canvas.drawText("Давл (×10 бар)", 310f, 30f, labelPaint.apply { color = Color.parseColor("#34D399") })
    }

    private fun drawSeries(
        canvas: Canvas,
        data: ArrayDeque<Float>,
        paint: Paint,
        w: Float,
        h: Float,
        maxY: Float
    ) {
        if (data.size < 2) return
        val path = Path()
        val stepX = w / (maxPoints - 1).toFloat()
        val startX = w - (data.size - 1) * stepX
        data.forEachIndexed { idx, v ->
            val clamped = v.coerceIn(0f, maxY)
            val x = startX + idx * stepX
            val y = h - (clamped / maxY) * (h - 48f) - 12f
            if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, paint)
    }
}
