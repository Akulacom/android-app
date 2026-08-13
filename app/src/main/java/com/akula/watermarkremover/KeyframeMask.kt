package com.akula.watermarkremover

import android.graphics.RectF

/**
 * Состояние одной маски в конкретный момент времени.
 *
 * trackId позволяет держать несколько независимых watermark одновременно.
 * active=false означает: данный watermark в этот момент отсутствует.
 */
data class MaskKeyframe(
    val timeMs: Long,
    val rect: RectF,
    val active: Boolean = true,
    val trackId: Int = 0,
    val confidence: Float = 1f
)

object MaskTracker {

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
     * Все активные маски в данный момент. Это основа multi-watermark режима:
     * на одном кадре может быть сразу несколько логотипов.
     */
    fun activeRectsAtTime(keyframes: List<MaskKeyframe>, timeMs: Long): List<RectF> {
        if (keyframes.isEmpty()) return emptyList()

        return keyframes
            .groupBy { it.trackId }
            .values
            .mapNotNull { track ->
                val sorted = track.sortedBy { it.timeMs }
                val state = nearest(sorted, timeMs)
                if (state.active && state.rect.width() >= 2f && state.rect.height() >= 2f) {
                    RectF(state.rect)
                } else {
                    null
                }
            }
    }

    /** Совместимость со старым кодом: возвращает первую активную маску. */
    fun interpolate(sortedKeyframes: List<MaskKeyframe>, timeMs: Long): RectF {
        return activeRectsAtTime(sortedKeyframes, timeMs).firstOrNull()
            ?: RectF(0f, 0f, 0f, 0f)
    }

    /**
     * Строит FFmpeg delogo-цепочку для всех независимых trackId.
     * Каждый трек включается только в тех временных сегментах, где active=true.
     * Поэтому один watermark может исчезнуть, другой появиться, а два могут
     * существовать одновременно.
     */
    fun buildTrackedDelogoFilter(
        keyframes: List<MaskKeyframe>,
        durationMs: Long,
        maxSegments: Int = 360
    ): String {
        require(keyframes.isNotEmpty()) { "Нужен хотя бы один keyframe" }

        val tracks = keyframes.groupBy { it.trackId }
        val totalMs = durationMs.coerceAtLeast(1L)
        val desiredStepMs = 120L
        val wantedSegments = (totalMs / desiredStepMs).toInt().coerceAtLeast(1)
        val segments = wantedSegments.coerceAtMost(maxSegments).coerceAtLeast(1)
        val stepMs = (totalMs / segments).coerceAtLeast(1L)

        val filters = ArrayList<String>()

        for ((_, rawTrack) in tracks) {
            val track = rawTrack.sortedBy { it.timeMs }
            if (track.isEmpty()) continue

            if (track.size == 1) {
                val k = track[0]
                if (!k.active || k.rect.width() < 2f || k.rect.height() < 2f) continue
                val r = k.rect
                filters.add(
                    "delogo=x=${r.left.toInt().coerceAtLeast(0)}:" +
                        "y=${r.top.toInt().coerceAtLeast(0)}:" +
                        "w=${r.width().toInt().coerceAtLeast(2)}:" +
                        "h=${r.height().toInt().coerceAtLeast(2)}:band=1:show=0"
                )
                continue
            }

            for (i in 0 until segments) {
                val t0 = i * stepMs
                val t1 = if (i == segments - 1) totalMs else (i + 1) * stepMs
                val mid = (t0 + t1) / 2L
                val k = nearest(track, mid)
                if (!k.active || k.confidence < 0.20f) continue

                val r = k.rect
                if (r.width() < 2f || r.height() < 2f) continue

                val x = r.left.toInt().coerceAtLeast(0)
                val y = r.top.toInt().coerceAtLeast(0)
                val w = r.width().toInt().coerceAtLeast(2)
                val h = r.height().toInt().coerceAtLeast(2)
                val t0s = t0 / 1000.0
                val t1s = t1 / 1000.0

                filters.add(
                    "delogo=x=$x:y=$y:w=$w:h=$h:band=1:show=0:" +
                        "enable='between(t,$t0s,$t1s)'"
                )
            }
        }

        return if (filters.isEmpty()) "null" else filters.joinToString(",")
    }
}
