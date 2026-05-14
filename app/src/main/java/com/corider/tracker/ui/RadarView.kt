package com.corider.tracker.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.corider.tracker.RideState
import com.corider.tracker.RiderSnapshot
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class RadarView(context: Context) : View(context) {
    private var state = RideState()
    private val background = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(203, 213, 225)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val ownPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(37, 99, 235) }
    private val riderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(16, 185, 129) }
    private val stalePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(148, 163, 184) }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(15, 23, 42)
        textSize = 32f
    }
    private val mutedTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(100, 116, 139)
        textSize = 28f
        textAlign = Paint.Align.CENTER
    }

    fun setState(next: RideState) {
        state = next
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val rect = RectF(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, 18f, 18f, background)

        val own = state.ownLocation
        if (own == null) {
            canvas.drawText("Waiting for location", width / 2f, height / 2f, mutedTextPaint)
            return
        }

        val now = System.currentTimeMillis()
        val riders = state.riders.values.toList()
        val centerX = width / 2f
        val centerY = height / 2f
        val radius = min(width, height) * 0.42f
        val maxMeters = scaleMeters(own, riders)

        drawGrid(canvas, centerX, centerY, radius, maxMeters)
        canvas.drawCircle(centerX, centerY, 13f, ownPaint)
        canvas.drawText("You", centerX + 18f, centerY - 18f, textPaint)

        riders.forEach { rider ->
            val (dxMeters, dyMeters) = rider.offsetMetersFrom(own)
            val distance = hypot(dxMeters.toDouble(), dyMeters.toDouble()).toFloat()
            val clamp = if (distance > maxMeters) maxMeters / distance else 1f
            val x = centerX + (dxMeters * clamp / maxMeters) * radius
            val y = centerY - (dyMeters * clamp / maxMeters) * radius
            val paint = if (rider.isStale(now)) stalePaint else riderPaint
            canvas.drawCircle(x, y, 12f, paint)
            canvas.drawText(rider.label.take(14), x + 18f, y - 14f, textPaint)
        }
    }

    private fun drawGrid(canvas: Canvas, centerX: Float, centerY: Float, radius: Float, maxMeters: Float) {
        canvas.drawCircle(centerX, centerY, radius, grid)
        canvas.drawCircle(centerX, centerY, radius * 0.66f, grid)
        canvas.drawCircle(centerX, centerY, radius * 0.33f, grid)
        canvas.drawLine(centerX - radius, centerY, centerX + radius, centerY, grid)
        canvas.drawLine(centerX, centerY - radius, centerX, centerY + radius, grid)
        canvas.drawText("${maxMeters.toInt()} m", centerX, centerY - radius - 14f, mutedTextPaint)
    }

    private fun scaleMeters(own: RiderSnapshot, riders: List<RiderSnapshot>): Float {
        val farthest = riders.maxOfOrNull { own.distanceTo(it) } ?: 250f
        val padded = max(250f, farthest * 1.25f)
        return ceil(padded / 250f) * 250f
    }
}

