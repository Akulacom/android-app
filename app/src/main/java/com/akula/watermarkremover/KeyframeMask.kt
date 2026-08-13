package com.akula.watermarkremover

import android.graphics.RectF

/**
 * Одна точка трекинга. active=false означает, что в этот момент watermark
 * отсутствует и никакой delogo применять нельзя.
 */
data class MaskKeyframe(
    val timeMs: Long,
    val rect: RectF,
    val active: Boolean = true
)

object MaskTracker {

    /**
     * Для авто-режима нам НЕ нужна линейная "поездка" маски между далёкими
     * позициями: watermark может мгновенно прыгнуть после монтажной склейки.
     * Поэтому берём ближайшую по времени точку. При частом семплировании
     * (300-500 мс) это даёт стабильную ступенчатую траекторию без размазывания
     * маски через весь кадр.
     */
    private fun nearest(sorted: List<MaskKeyframe>, timeMs: Long): MaskKeyframe {
        if (sorted.size == 1) return sorted[0]
        if (timeMs <= sorted.first().timeMs) return sorted.first()
        if (timeMs >= sorted.last().timeMs) return sorted.last()

        var best = sorted.first()
        var bestDistance = kotlin.math.abs(best.timeMs - timeMs)
        for (item in sorted) {
            val distance = kotlin.math.abs(item.timeMs - timeMs)
            if (distance < bestDistance) {
                best = item
                bestDistance = distance
            }
        }
        return best
    }

    /**
     * Строит цепочку delogo только там, где watermark реально активен.
     * active=false полностью пропускается, поэтому между появлениями логотипа
     * приложение больше не размывает случайные места кадра.
     */
    fun buildTrackedDelogoFilter(
        keyframes: List<MaskKeyframe>,
        durationMs: Long,
        maxSegments: Int = 180
    ): String {
        val sorted = keyframes.sortedBy { it.timeMs }
        require(sorted.isNotEmpty()) { "Нужен хотя бы один keyframe" }

        if (sorted.size == 1) {
            val k = sorted[0]
            if (!k.active || k.rect.width() < 2f || k.rect.height() < 2f) return "null"
            val r = k.rect
            return "delogo=x=${r.left.toInt()}:y=${r.top.toInt()}:" +
                "w=${r.width().toInt().coerceAtLeast(2)}:" +
                "h=${r.height().toInt().coerceAtLeast(2)}:show=0"
        }

        val totalMs = durationMs.coerceAtLeast(1L)
        val desiredStepMs = 250L
        val wantedSegments = (totalMs / desiredStepMs).toInt().coerceAtLeast(1)
        val segments = wantedSegments.coerceAtMost(maxSegments).coerceAtLeast(1)
        val stepMs = (totalMs / segments).coerceAtLeast(1L)

        val filters = ArrayList<String>()
        for (i in 0 until segments) {
            val t0 = i * stepMs
            val t1 = if (i == segments - 1) totalMs else (i + 1) * stepMs
            val mid = (t0 + t1) / 2L
            val k = nearest(sorted, mid)
            if (!k.active) continue

            val r = k.rect
            if (r.width() < 2f || r.height() < 2f) continue

            val x = r.left.toInt().coerceAtLeast(0)
            val y = r.top.toInt().coerceAtLeast(0)
            val w = r.width().toInt().coerceAtLeast(2)
            val h = r.height().toInt().coerceAtLeast(2)
            val t0s = t0 / 1000.0
            val t1s = t1 / 1000.0

            filters.add(
                "delogo=x=$x:y=$y:w=$w:h=$h:show=0:" +
                    "enable='between(t,$t0s,$t1s)'"
            )
        }

        return if (filters.isEmpty()) "null" else filters.joinToString(",")
    }
}
