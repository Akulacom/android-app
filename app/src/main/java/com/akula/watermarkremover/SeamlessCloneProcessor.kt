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
 * Быстрый режим без нейросети.
 *
 * Для каждого короткого временного сегмента подбирается похожий чистый участок
 * фона рядом с watermark. Донор берётся из того же текущего кадра, а его
 * направление стабилизируется между соседними сегментами, чтобы заплатка не
 * прыгала и не мерцала.
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

    private data class SourcePick(
        val rect: RectF,
        val score: Double,
        val offsetX: Int,
        val offsetY: Int
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
                    "-crf", "17",
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
                            val logs = session.allLogsAsString ?: ""
                            val useful = logs.lines()
                                .filter { line ->
                                    line.contains("Error", true) ||
                                        line.contains("Invalid", true) ||
                                        line.contains("Failed", true) ||
                                        line.contains("filter", true) ||
                                        line.contains("overlay", true) ||
                                        line.contains("crop", true)
                                }
                                .takeLast(24)
                                .joinToString("\n")
                            val fallback = logs.lines().takeLast(40).joinToString("\n")
                            callback.onError(
                                "Seamless clone FFmpeg: ${session.returnCode}\n\n" +
                                    if (useful.isNotBlank()) useful else fallback
                            )
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
        val track = keyframes.sortedBy { it.timeMs }
        if (track.isEmpty()) return emptyList()

        val totalMs = durationMs.coerceAtLeast(1L)

        // До 96 временных сегментов. Это точнее следует за плавающим watermark,
        // но остаётся достаточно лёгким для мобильного FFmpeg.
        val maxSegments = 96L
        val stepMs = maxOf(250L, (totalMs + maxSegments - 1L) / maxSegments)
        val result = ArrayList<Op>()

        var t0 = 0L
        var current: Op? = null
        var preferredOffset: Pair<Int, Int>? = null

        while (t0 < totalMs) {
            val t1 = (t0 + stepMs).coerceAtMost(totalMs)
            val mid = (t0 + t1) / 2L
            val state = repairState(track, mid)

            if (state.active && state.rect.width() >= 4f && state.rect.height() >= 4f) {
                val dst = sanitizeAndPad(state.rect, videoWidth, videoHeight)
                val frame = retriever.getFrameAtTime(
                    mid * 1000L,
                    MediaMetadataRetriever.OPTION_CLOSEST
                )

                val pick = if (frame != null) {
                    try {
                        chooseBestSource(
                            bitmap = frame,
                            dst = dst,
                            videoWidth = videoWidth,
                            videoHeight = videoHeight,
                            preferredOffset = preferredOffset
                        )
                    } finally {
                        frame.recycle()
                    }
                } else {
                    val fallback = fallbackSource(dst, videoWidth, videoHeight)
                    SourcePick(
                        rect = fallback,
                        score = Double.MAX_VALUE,
                        offsetX = (fallback.left - dst.left).toInt(),
                        offsetY = (fallback.top - dst.top).toInt()
                    )
                }

                preferredOffset = pick.offsetX to pick.offsetY
                val src = pick.rect

                if (current != null &&
                    current.endMs == t0 &&
                    closeRect(current.dst, dst, 4f) &&
                    closeRect(current.src, src, 6f)
                ) {
                    current.endMs = t1
                } else {
                    current = Op(t0, t1, dst, src)
                    result.add(current)
                }
            } else {
                current = null
                preferredOffset = null
            }

            t0 = t1
        }

        if (result.size <= maxSegments.toInt()) return result

        val stride = kotlin.math.ceil(result.size / maxSegments.toDouble())
            .toInt()
            .coerceAtLeast(1)
        val kept = result.filterIndexed { index, _ -> index % stride == 0 }.toMutableList()
        for (i in 0 until kept.size - 1) {
            kept[i].endMs = kept[i + 1].startMs
        }
        kept.lastOrNull()?.endMs = totalMs
        return kept
    }

    /** Закрывает только короткий провал трекера между двумя активными точками. */
    private fun repairState(sorted: List<MaskKeyframe>, timeMs: Long): MaskKeyframe {
        val direct = nearest(sorted, timeMs)
        if (direct.active) return direct

        val previous = sorted.lastOrNull { it.timeMs <= timeMs && it.active }
        val next = sorted.firstOrNull { it.timeMs >= timeMs && it.active }
        if (previous == null || next == null) return direct
        if (next.timeMs - previous.timeMs > 1000L) return direct

        val pw = previous.rect.width().coerceAtLeast(1f)
        val ph = previous.rect.height().coerceAtLeast(1f)
        val nw = next.rect.width().coerceAtLeast(1f)
        val nh = next.rect.height().coerceAtLeast(1f)
        val sizeRatio = max(max(pw / nw, nw / pw), max(ph / nh, nh / ph))
        if (sizeRatio > 1.8f) return direct

        val chosen = if (
            kotlin.math.abs(previous.timeMs - timeMs) <=
            kotlin.math.abs(next.timeMs - timeMs)
        ) previous else next

        return MaskKeyframe(
            timeMs = timeMs,
            rect = RectF(chosen.rect),
            active = true,
            trackId = chosen.trackId,
            confidence = chosen.confidence * 0.85f
        )
    }

    private fun nearest(sorted: List<MaskKeyframe>, timeMs: Long): MaskKeyframe {
        if (sorted.size == 1) return sorted[0]
        if (timeMs <= sorted.first().timeMs) return sorted.first()
        if (timeMs >= sorted.last().timeMs) return sorted.last()

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
        // Небольшой запас закрывает полупрозрачный ореол букв, но не превращает
        // узкий watermark в большой прямоугольник восстановления.
        val px = max(4f, rect.width() * 0.06f)
        val py = max(4f, rect.height() * 0.10f)
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
        videoHeight: Int,
        preferredOffset: Pair<Int, Int>?
    ): SourcePick {
        val w = dst.width().toInt().coerceAtLeast(4)
        val h = dst.height().toInt().coerceAtLeast(4)
        val dx = dst.left.toInt()
        val dy = dst.top.toInt()

        val nearGap = max(3, min(w, h) / 10)
        val farGap = max(7, min(w, h) / 4)

        val offsets = LinkedHashSet<Pair<Int, Int>>()

        if (preferredOffset != null) offsets.add(preferredOffset)

        offsets.addAll(
            listOf(
                Pair(w + nearGap, 0), Pair(-(w + nearGap), 0),
                Pair(0, h + nearGap), Pair(0, -(h + nearGap)),
                Pair(w + nearGap, h / 2), Pair(w + nearGap, -h / 2),
                Pair(-(w + nearGap), h / 2), Pair(-(w + nearGap), -h / 2),
                Pair(w / 2, h + nearGap), Pair(-w / 2, h + nearGap),
                Pair(w / 2, -(h + nearGap)), Pair(-w / 2, -(h + nearGap)),
                Pair(w + farGap, 0), Pair(-(w + farGap), 0),
                Pair(0, h + farGap), Pair(0, -(h + farGap))
            )
        )

        val candidates = ArrayList<SourcePick>()

        for ((ox, oy) in offsets) {
            val x = dx + ox
            val y = dy + oy
            if (x < 0 || y < 0 || x + w > videoWidth || y + h > videoHeight) continue

            val rect = RectF(
                x.toFloat(),
                y.toFloat(),
                (x + w).toFloat(),
                (y + h).toFloat()
            )
            if (RectF.intersects(rect, dst)) continue

            val score = sourceScore(bitmap, dst, rect)
            candidates.add(SourcePick(rect, score, ox, oy))
        }

        if (candidates.isEmpty()) {
            val fallback = fallbackSource(dst, videoWidth, videoHeight)
            return SourcePick(
                rect = fallback,
                score = Double.MAX_VALUE,
                offsetX = (fallback.left - dst.left).toInt(),
                offsetY = (fallback.top - dst.top).toInt()
            )
        }

        val best = candidates.minByOrNull { it.score } ?: candidates.first()

        // Гистерезис: если предыдущий относительный донор всё ещё почти такой же
        // хороший, оставляем его. Это сильно уменьшает мерцание и прыжки заплатки.
        if (preferredOffset != null) {
            val preferred = candidates.firstOrNull {
                it.offsetX == preferredOffset.first && it.offsetY == preferredOffset.second
            }
            if (preferred != null && preferred.score <= best.score * 1.12 + 10.0) {
                return preferred
            }
        }

        return best
    }

    private fun sourceScore(bitmap: Bitmap, dst: RectF, src: RectF): Double {
        return borderScore(bitmap, dst, src) + gradientScore(bitmap, dst, src) * 1.6
    }

    private fun borderScore(bitmap: Bitmap, dst: RectF, src: RectF): Double {
        val w = min(dst.width().toInt(), src.width().toInt()).coerceAtLeast(2)
        val h = min(dst.height().toInt(), src.height().toInt()).coerceAtLeast(2)
        val samples = 32
        var total = 0.0
        var count = 0

        fun diff(x1: Int, y1: Int, x2: Int, y2: Int) {
            val p1 = bitmap.getPixel(
                x1.coerceIn(0, bitmap.width - 1),
                y1.coerceIn(0, bitmap.height - 1)
            )
            val p2 = bitmap.getPixel(
                x2.coerceIn(0, bitmap.width - 1),
                y2.coerceIn(0, bitmap.height - 1)
            )
            val dr = Color.red(p1) - Color.red(p2)
            val dg = Color.green(p1) - Color.green(p2)
            val db = Color.blue(p1) - Color.blue(p2)
            total += abs(dr).toDouble() + abs(dg).toDouble() + abs(db).toDouble()
            count++
        }

        val outside = 3
        for (i in 0 until samples) {
            val f = i.toFloat() / (samples - 1).toFloat()

            val dstX = dst.left.toInt() + (f * (w - 1)).toInt()
            val srcX = src.left.toInt() + (f * (w - 1)).toInt()
            diff(dstX, dst.top.toInt() - outside, srcX, src.top.toInt())
            diff(dstX, dst.bottom.toInt() + outside - 1, srcX, src.bottom.toInt() - 1)

            val dstY = dst.top.toInt() + (f * (h - 1)).toInt()
            val srcY = src.top.toInt() + (f * (h - 1)).toInt()
            diff(dst.left.toInt() - outside, dstY, src.left.toInt(), srcY)
            diff(dst.right.toInt() + outside - 1, dstY, src.right.toInt() - 1, srcY)
        }

        return if (count == 0) Double.MAX_VALUE else total / count.toDouble()
    }

    /**
     * Сравнивает направление и силу локальных перепадов яркости вокруг рамки.
     * Это помогает не брать кусок похожего цвета, но с совсем другой текстурой.
     */
    private fun gradientScore(bitmap: Bitmap, dst: RectF, src: RectF): Double {
        val samples = 20
        var total = 0.0
        var count = 0

        fun luma(pixel: Int): Double {
            return Color.red(pixel) * 0.299 +
                Color.green(pixel) * 0.587 +
                Color.blue(pixel) * 0.114
        }

        fun gradientDiff(
            dx1: Int, dy1: Int, dx2: Int, dy2: Int,
            sx1: Int, sy1: Int, sx2: Int, sy2: Int
        ) {
            val d1 = bitmap.getPixel(
                dx1.coerceIn(0, bitmap.width - 1),
                dy1.coerceIn(0, bitmap.height - 1)
            )
            val d2 = bitmap.getPixel(
                dx2.coerceIn(0, bitmap.width - 1),
                dy2.coerceIn(0, bitmap.height - 1)
            )
            val s1 = bitmap.getPixel(
                sx1.coerceIn(0, bitmap.width - 1),
                sy1.coerceIn(0, bitmap.height - 1)
            )
            val s2 = bitmap.getPixel(
                sx2.coerceIn(0, bitmap.width - 1),
                sy2.coerceIn(0, bitmap.height - 1)
            )

            val gd = luma(d2) - luma(d1)
            val gs = luma(s2) - luma(s1)
            total += abs(gd - gs)
            count++
        }

        for (i in 0 until samples) {
            val f = i.toFloat() / (samples - 1).toFloat()

            val dx = dst.left.toInt() + (f * (dst.width() - 1f)).toInt()
            val sx = src.left.toInt() + (f * (src.width() - 1f)).toInt()
            gradientDiff(
                dx, dst.top.toInt() - 4,
                dx, dst.top.toInt() - 2,
                sx, src.top.toInt(),
                sx, src.top.toInt() + 2
            )
            gradientDiff(
                dx, dst.bottom.toInt() + 3,
                dx, dst.bottom.toInt() + 1,
                sx, src.bottom.toInt() - 1,
                sx, src.bottom.toInt() - 3
            )

            val dy = dst.top.toInt() + (f * (dst.height() - 1f)).toInt()
            val sy = src.top.toInt() + (f * (src.height() - 1f)).toInt()
            gradientDiff(
                dst.left.toInt() - 4, dy,
                dst.left.toInt() - 2, dy,
                src.left.toInt(), sy,
                src.left.toInt() + 2, sy
            )
            gradientDiff(
                dst.right.toInt() + 3, dy,
                dst.right.toInt() + 1, dy,
                src.right.toInt() - 1, sy,
                src.right.toInt() - 3, sy
            )
        }

        return if (count == 0) 0.0 else total / count.toDouble()
    }

    private fun fallbackSource(dst: RectF, videoWidth: Int, videoHeight: Int): RectF {
        val w = dst.width().toInt().coerceAtLeast(2)
        val h = dst.height().toInt().coerceAtLeast(2)
        val gap = max(4, min(w, h) / 10)

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
        return abs(a.left - b.left) <= tolerance &&
            abs(a.top - b.top) <= tolerance &&
            abs(a.right - b.right) <= tolerance &&
            abs(a.bottom - b.bottom) <= tolerance
    }

    /**
     * Clone-fill с мягким alpha-feather. Никакого blur к самому видео не
     * применяется: используется реальная текстура соседнего участка кадра.
     */
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

            // Более широкий мягкий край уменьшает заметность прямоугольника,
            // при этом центральная часть остаётся полностью заменённой.
            val feather = max(4, min(24, min(w, h) / 4))

            sb.append("[donor$i]crop=$w:$h:$sx:$sy,format=yuva444p,")
            sb.append("geq=lum='lum(X,Y)':cb='cb(X,Y)':cr='cr(X,Y)':")
            sb.append("a='255*max(0,min(1,min(min(X/$feather,(W-1-X)/$feather),min(Y/$feather,(H-1-Y)/$feather))))'")
            sb.append("[patch$i];")

            val baseIn = "[base$i]"
            val baseOut = if (i == ops.lastIndex) "[merged]" else "[base${i + 1}]"
            sb.append(baseIn)
            sb.append("[patch$i]overlay=x=$dx:y=$dy:shortest=1:format=auto:enable='between(t,${sec(op.startMs)},${sec(op.endMs)})'")
            sb.append(baseOut)
            sb.append(";")
        }

        // Сохраняем исходное разрешение. Раньше принудительный scale=1080
        // мог дополнительно мылить 720p и зря увеличивать размер файла.
        sb.append("[merged]format=yuv420p[vout]")
        return sb.toString()
    }

    private fun sec(ms: Long): String = String.format(Locale.US, "%.3f", ms / 1000.0)
}
