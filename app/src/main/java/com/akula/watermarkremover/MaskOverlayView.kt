package com.akula.watermarkremover

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Простая вью поверх видео: пользователь пальцем растягивает
 * прямоугольник над текстом/вотермарком, который нужно убрать.
 * Координаты доступны в maskRect (в пикселях самой View).
 */
class MaskOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    val maskRect = RectF()

    private var startX = 0f
    private var startY = 0f

    private val boxPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val fillPaint = Paint().apply {
        color = Color.argb(80, 255, 0, 0)
        style = Paint.Style.FILL
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                maskRect.set(startX, startY, startX, startY)
            }
            MotionEvent.ACTION_MOVE -> {
                maskRect.set(
                    minOf(startX, event.x),
                    minOf(startY, event.y),
                    maxOf(startX, event.x),
                    maxOf(startY, event.y)
                )
                invalidate()
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!maskRect.isEmpty) {
            canvas.drawRect(maskRect, fillPaint)
            canvas.drawRect(maskRect, boxPaint)
        }
    }

    fun hasMask(): Boolean = !maskRect.isEmpty && maskRect.width() > 4 && maskRect.height() > 4
}
