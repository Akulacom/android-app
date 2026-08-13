package com.akula.watermarkremover

import android.graphics.RectF

/**
 * Одна "точка" трекинга: положение маски (в реальных пикселях видео)
 * в конкретный момент времени.
 */
data class MaskKeyframe(val timeMs: Long, val rect: RectF)

/**
 * Линейная интерполяция прямоугольника маски между ключевыми кадрами.
 * Если кадр один — маска статична на всё видео (старое поведение).
 * Если кадров несколько — между ними считается линейное движение
 * (простой трекинг без компьютерного зрения, но для большинства
 * вотермарков, которые двигаются предсказуемо/по прямой, этого достаточно).
 */
object MaskTracker {

    fun interpolate(sortedKeyframes: List<MaskKeyframe>, timeMs: Long): RectF {
        require(sortedKeyframes.isNotEmpty()) { "Нужен хотя бы один keyframe" }
        if (sortedKeyframes.size == 1) return sortedKeyframes[0].rect

        val first = sortedKeyframes.first()
        val last = sortedKeyframes.last()
        if (timeMs <= first.timeMs) return first.rect
        if (timeMs >= last.timeMs) return last.rect

        for (i in 0 until sortedKeyframes.size - 1) {
            val a = sortedKeyframes[i]
            val b = sortedKeyframes[i + 1]
            if (timeMs in a.timeMs..b.timeMs) {
                val span = (b.timeMs - a.timeMs).coerceAtLeast(1)
                val frac = (timeMs - a.timeMs).toFloat() / span.toFloat()
                return RectF(
                    lerp(a.rect.left, b.rect.left, frac),
                    lerp(a.rect.top, b.rect.top, frac),
                    lerp(a.rect.right, b.rect.right, frac),
                    lerp(a.rect.bottom, b.rect.bottom, frac)
                )
            }
        }
        return last.rect
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    /**
     * Строит цепочку FFmpeg-фильтров delogo: видео режется на короткие
     * отрезки времени (enable=between(t,t0,t1)), для каждого отрезка
     * координаты маски берутся из интерполяции. Итог — маска "едет"
     * ступенчато по кадрам видео вслед за вотермарком.
     *
     * maxSegments ограничивает длину цепочки фильтров, чтобы команда
     * FFmpeg не разрослась до неадекватного размера на длинных видео.
     */
    fun buildTrackedDelogoFilter(
        keyframes: List<MaskKeyframe>,
        durationMs: Long,
        maxSegments: Int = 120
    ): String {
        val sorted = keyframes.sortedBy { it.timeMs }
        if (sorted.size == 1) {
            val r = sorted[0].rect
            return "delogo=x=${r.left.toInt()}:y=${r.top.toInt()}:" +
                "w=${r.width().toInt()}:h=${r.height().toInt()}:show=0"
        }

        val totalMs = durationMs.coerceAtLeast(1)
        val segments = maxSegments.coerceAtMost((totalMs / 150).toInt().coerceAtLeast(1))
        val stepMs = totalMs / segments

        val sb = StringBuilder()
        for (i in 0 until segments) {
            val t0 = i * stepMs
            val t1 = if (i == segments - 1) totalMs else (i + 1) * stepMs
            val midT = (t0 + t1) / 2
            val rect = interpolate(sorted, midT)
            val t0s = t0 / 1000.0
            val t1s = t1 / 1000.0
            sb.append(
                "delogo=x=${rect.left.toInt()}:y=${rect.top.toInt()}:" +
                    "w=${rect.width().toInt().coerceAtLeast(2)}:" +
                    "h=${rect.height().toInt().coerceAtLeast(2)}:show=0:" +
                    "enable='between(t,$t0s,$t1s)'"
            )
            if (i != segments - 1) sb.append(",")
        }
        return sb.toString()
    }
}
