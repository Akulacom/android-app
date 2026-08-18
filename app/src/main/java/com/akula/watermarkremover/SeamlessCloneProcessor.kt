package com.akula.watermarkremover

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.Statistics
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Быстрый режим без размытого delogo-пятна.
 *
 * Для каждого короткого временного сегмента подбирается похожий участок фона
 * рядом с watermark. Этот участок берётся из ТОГО ЖЕ текущего кадра, поэтому
 * он двигается вместе с камерой. Края клона смешиваются мягкой alpha-маской.
 */
object SeamlessCloneProcessor {

    interface Callback {
        fun onProgress(percent: Int)
        fun onSuccess(outputPath: String)
        fun onError(message: String)
    }

    private data class Op(
        var startMs: Long,
        var endMs: Long,
        val dst: RectF,
        val src: RectF
    )

    fun process(
        inputPath: String,
        outputPath: String,
        keyframes: List<MaskKeyframe>,
        durationMs: Long,
        videoWidth: Int,
        videoHeight: Int,
        callback: Callback
    ) {
        Thread {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(inputPath)
                val ops = buildOps(
                    retriever = retriever,
                    keyframes = keyframes,
                    durationMs = durationMs,
                    videoWidth = videoWidth,
                    videoHeight = videoHeight
                )

                if (ops.isEmpty()) {
                    callback.onError("Не удалось построить seamless-маску")
                    return@Thread
                }

                callback.onProgress(40)
                val filter = buildFilter(ops)
                val cmd = arrayOf(
                    "-y",
                    "-i", inputPath,
                    "-filter_complex", filter,
                    "-map", "[vout]",
                    "-map", "0:a?",
                    "-c:v", "libx264",
                    "-preset", "medium",
                    "-crf", "18",
                    "-pix_fmt", "yuv420p",
                    "-movflags", "+faststart",
                    "-c:a", "copy",
                    outputPath
                )

                FFmpegKit.executeWithArgumentsAsync(
                    cmd,
                    { session: FFmpegSession ->
                        if (ReturnCode.isSuccess(session.returnCode)) {
                            val out = File(outputPath)
                            if (out.exists() && out.length() > 0L) {
                                callback.onProgress(100)
                                callback.onSuccess(outputPath)
                            } else {
                                callback.onError("FFmpeg завершился без готового файла")
                            }
                        } else {
                            val tail = (session.allLogsAsString ?: "")
                                .lines()
                                .takeLast(30)
                                .joinToString("\n")
                            callback.onError("Seamless clone FFmpeg: ${session.returnCode}\n\n$tail")
                        }
                    },
                    null
                ) { stats: Statistics ->
                    if (durationMs > 0L) {
                        val ff = ((stats.time.toFloat() / durationMs.toFloat()) * 100f)
                            .toInt()
                            .coerceIn(0, 100)
                        callback.onProgress((40 + ff * 60 / 100).coerceIn(40, 99))
                    }
                }
            } catch (t: Throwable) {
                callback.onError("Seamless clone: ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                try { retriever.release() } catch (_: Throwable) {}
            }
        }.start()
    }

    private fun buildOps(
        retriever: MediaMetadataRetriever,
        keyframes: List<MaskKeyframe>,
        durationMs: Long,
        videoWidth: Int,
        videoHeight: Int
    ): List<Op> {
        if (keyframes.isEmpty()) return emptyList()

        val totalMs = durationMs.coerceAtLeast(1L)
        val stepMs = 350L
        val result = ArrayList<Op>()

        for ((_, rawTrack) in keyframes.groupBy { it.trackId }) {
            val track = rawTrack.sortedBy { it.timeMs }
            if (track.isEmpty()) continue

            var t0 = 0L
            var current: Op? = null
            while (t0 < totalMs) {
                val t1 = (t0 + stepMs).coerceAtMost(totalMs)
                val mid = (t0 + t1) / 2L
                val state = nearest(track, mid)

                if (state.active && state.confidence >= 0.20f &&
                    state.rect.width() >= 4f && state.rect.height() >= 4f
                ) {
                    val dst = sanitizeAndPad(state.rect, videoWidth, videoHeight)
                    val frame = retriever.getFrameAtTime(
                        mid * 1000L,
                        MediaMetadataRetriever.OPTION_CLOSEST
                    )

                    val src = if (frame != null) {
                        try {
                            chooseBestSource(frame, dst, videoWidth, videoHeight)
                        } finally {
                            frame.recycle()
                        }
                    } else {
                        fallbackSource(dst, videoWidth, videoHeight)
                    }

                    if (current != null && current.endMs == t0 &&
                        closeRect(current.dst, dst, 5f) && closeRect(current.src, src, 8f)
                    ) {
                        current.endMs = t1
                    } else {
                        current = Op(t0, t1, dst, src)
                        result.add(current)
                    }
                } else {
                    current = null
                }

                t0 = t1
            }
        }

        // Ограничиваем сложность filter_complex на длинных роликах.
        return if (result.size <= 90) result else {
            val stride = (result.size / 90.0).toInt().coerceAtLeast(1)
            result.filterIndexed { index, _ -> index % stride == 0 }.take(90)
        }
    }

    private fun nearest(sorted: List<MaskKeyframe>, timeMs: Long): MaskKeyframe {
        if (sorted.size == 1) return sorted[0]
        var best = sorted.first()
        var bestD = kotlin.math.abs(best.timeMs - timeMs)
        for (k in sorted) {
            val d = kotlin.math.abs(k.timeMs - timeMs)
            if (d < bestD) {
                best = k
                bestD = d
            }
        }
        return best
    }

    private fun sanitizeAndPad(rect: RectF, w: Int, h: Int): RectF {
        val px = max(2f, rect.width() * 0.03f)
        val py = max(2f, rect.height() * 0.06f)
        val l = (rect.left - px).coerceIn(1f, w.toFloat() - 3f)
        val t = (rect.top - py).coerceIn(1f, h.toFloat() - 3f)
        val r = (rect.right + px).coerceIn(l + 2f, w.toFloat() - 1f)
        val b = (rect.bottom + py).coerceIn(t + 2f, h.toFloat() - 1f)
        return RectF(l, t, r, b)
    }

    private fun chooseBestSource(
        bitmap: Bitmap,
        dst: RectF,
        videoWidth: Int,
        videoHeight: Int
    ): RectF {
        val w = dst.width().toInt().coerceAtLeast(4)
        val h = dst.height().toInt().coerceAtLeast(4)
        val dx = dst.left.toInt()
        val dy = dst.top.toInt()
        val gap = max(8, min(w, h) / 5)

        val candidates = ArrayList<RectF>()
        val offsets = listOf(
            Pair(w + gap, 0), Pair(-(w + gap), 0),
            Pair(0, h + gap), Pair(0, -(h + gap)),
            Pair(w + gap, h / 2), Pair(w + gap, -h / 2),
            Pair(-(w + gap), h / 2), Pair(-(w + gap), -h / 2),
            Pair(w / 2, h + gap), Pair(-w / 2, h + gap),
            Pair(w / 2, -(h + gap)), Pair(-w / 2, -(h + gap))
        )

        for ((ox, oy) in offsets) {
            val x = dx + ox
            val y = dy + oy
            if (x >= 0 && y >= 0 && x + w <= videoWidth && y + h <= videoHeight) {
                val c = RectF(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat())
                if (!RectF.intersects(c, dst)) candidates.add(c)
            }
        }

        if (candidates.isEmpty()) return fallbackSource(dst, videoWidth, videoHeight)

        var best = candidates.first()
        var bestScore = Double.MAX_VALUE
        for (candidate in candidates) {
            val score = borderScore(bitmap, dst, candidate)
            if (score < bestScore) {
                bestScore = score
                best = candidate
            }
        }
        return best
    }

    private fun borderScore(bitmap: Bitmap, dst: RectF, src: RectF): Double {
        val w = min(dst.width().toInt(), src.width().toInt()).coerceAtLeast(2)
        val h = min(dst.height().toInt(), src.height().toInt()).coerceAtLeast(2)
        val samples = 18
        var total = 0.0
        var count = 0

        fun diff(x1: Int, y1: Int, x2: Int, y2: Int) {
            val p1 = bitmap.getPixel(x1.coerceIn(0, bitmap.width - 1), y1.coerceIn(0, bitmap.height - 1))
            val p2 = bitmap.getPixel(x2.coerceIn(0, bitmap.width - 1), y2.coerceIn(0, bitmap.height - 1))
            val dr = Color.red(p1) - Color.red(p2)
            val dg = Color.green(p1) - Color.green(p2)
            val db = Color.blue(p1) - Color.blue(p2)
            total += abs(dr).toDouble() + abs(dg).toDouble() + abs(db).toDouble()
            count++
        }

        for (i in 0 until samples) {
            val fx = i.toFloat() / (samples - 1).toFloat()
            val fy = fx
            val x1 = dst.left.toInt() + (fx * (w - 1)).toInt()
            val x2 = src.left.toInt() + (fx * (w - 1)).toInt()
            diff(x1, dst.top.toInt(), x2, src.top.toInt())
            diff(x1, dst.bottom.toInt() - 1, x2, src.bottom.toInt() - 1)

            val y1 = dst.top.toInt() + (fy * (h - 1)).toInt()
            val y2 = src.top.toInt() + (fy * (h - 1)).toInt()
            diff(dst.left.toInt(), y1, src.left.toInt(), y2)
            diff(dst.right.toInt() - 1, y1, src.right.toInt() - 1, y2)
        }

        return if (count == 0) Double.MAX_VALUE else total / count.toDouble()
    }

    private fun fallbackSource(dst: RectF, videoWidth: Int, videoHeight: Int): RectF {
        val w = dst.width().toInt().coerceAtLeast(2)
        val h = dst.height().toInt().coerceAtLeast(2)
        val gap = max(8, min(w, h) / 5)

        val rightX = dst.right.toInt() + gap
        if (rightX + w <= videoWidth) {
            return RectF(rightX.toFloat(), dst.top, (rightX + w).toFloat(), dst.top + h)
        }

        val leftX = dst.left.toInt() - gap - w
        if (leftX >= 0) {
            return RectF(leftX.toFloat(), dst.top, (leftX + w).toFloat(), dst.top + h)
        }

        val belowY = dst.bottom.toInt() + gap
        if (belowY + h <= videoHeight) {
            return RectF(dst.left, belowY.toFloat(), dst.left + w, (belowY + h).toFloat())
        }

        val aboveY = (dst.top.toInt() - gap - h).coerceAtLeast(0)
        return RectF(dst.left, aboveY.toFloat(), dst.left + w, (aboveY + h).toFloat())
    }

    private fun closeRect(a: RectF, b: RectF, tolerance: Float): Boolean {
        return abs(a.left - b.left) <= tolerance && abs(a.top - b.top) <= tolerance &&
            abs(a.right - b.right) <= tolerance && abs(a.bottom - b.bottom) <= tolerance
    }

    private fun buildFilter(ops: List<Op>): String {
        val sb = StringBuilder()
        sb.append("[0:v]split=${ops.size + 1}[base0]")
        for (i in ops.indices) sb.append("[donor$i]")
        sb.append(";")

        for ((i, op) in ops.withIndex()) {
            val w = op.dst.width().toInt().coerceAtLeast(4)
            val h = op.dst.height().toInt().coerceAtLeast(4)
            val sx = op.src.left.toInt().coerceAtLeast(0)
            val sy = op.src.top.toInt().coerceAtLeast(0)
            val dx = op.dst.left.toInt().coerceAtLeast(0)
            val dy = op.dst.top.toInt().coerceAtLeast(0)
            val feather = max(2, min(14, min(w, h) / 6))
            val innerW = max(1, w - feather * 2)
            val innerH = max(1, h - feather * 2)
            val blur = max(1, feather / 2)

            sb.append("[donor$i]crop=$w:$h:$sx:$sy,format=rgba[patchRgb$i];")
            sb.append("color=c=black:s=${w}x${h}:r=30,format=gray,")
            sb.append("drawbox=x=$feather:y=$feather:w=$innerW:h=$innerH:color=white:t=fill,")
            sb.append("boxblur=luma_radius=$blur:luma_power=2[mask$i];")
            sb.append("[patchRgb$i][mask$i]alphamerge[patch$i];")

            val baseIn = "[base$i]"
            val baseOut = if (i == ops.lastIndex) "[merged]" else "[base${i + 1}]"
            sb.append(baseIn)
            sb.append("[patch$i]overlay=x=$dx:y=$dy:enable='between(t,${sec(op.startMs)},${sec(op.endMs)})'")
            sb.append(baseOut)
            sb.append(";")
        }

        sb.append("[merged]scale=-2:1080[vout]")
        return sb.toString()
    }

    private fun sec(ms: Long): String = String.format(Locale.US, "%.3f", ms / 1000.0)
}
