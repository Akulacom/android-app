package com.akula.watermarkremover

import android.graphics.Bitmap
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.Statistics
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * Быстрый режим удаления watermark через FFmpeg delogo.
 *
 * Важное отличие: перед запуском FFmpeg обработчик сам повторно проверяет
 * исходное видео через OCR. Это не даёт UI-автотрекеру случайно передать
 * десятки масок от текста на одежде, баннерах или других деталях сцены.
 */
object VideoProcessor {

    interface Callback {
        fun onProgress(percent: Int)
        fun onSuccess(outputPath: String)
        fun onError(message: String)
    }

    private data class TextHit(
        val timeMs: Long,
        val key: String,
        val text: String,
        val rect: RectF
    )

    private data class TextGroup(
        val key: String,
        val hits: MutableList<TextHit> = mutableListOf()
    )

    fun removeWatermarkAndExport(
        inputPath: String,
        outputPath: String,
        keyframes: List<MaskKeyframe>,
        durationMs: Long,
        callback: Callback
    ) {
        Thread {
            try {
                // UI keyframes остаются fallback. Сначала пытаемся получить более
                // надёжный OCR-трек непосредственно из файла, который уйдёт в FFmpeg.
                val verified = detectRepeatedTextWatermark(inputPath, durationMs)
                val processingKeyframes = verified ?: keyframes

                if (processingKeyframes.none {
                        it.active && it.rect.width() >= 2f && it.rect.height() >= 2f
                    }) {
                    callback.onError("Не найдено ни одной рабочей области watermark")
                    return@Thread
                }

                val delogoChain = MaskTracker.buildTrackedDelogoFilter(
                    processingKeyframes,
                    durationMs
                )

                if (delogoChain.isBlank() || delogoChain == "null") {
                    callback.onError("FFmpeg не получил активную область watermark")
                    return@Thread
                }

                val filter = "$delogoChain,scale=-2:1080"

                val cmd = arrayOf(
                    "-y",
                    "-i", inputPath,
                    "-vf", filter,
                    "-c:v", "libx264",
                    "-preset", "medium",
                    "-crf", "20",
                    "-c:a", "copy",
                    outputPath
                )

                FFmpegKit.executeWithArgumentsAsync(
                    cmd,
                    { session: FFmpegSession ->
                        if (ReturnCode.isSuccess(session.returnCode)) {
                            callback.onSuccess(outputPath)
                        } else {
                            val fullLog = session.allLogsAsString ?: ""
                            val tail = fullLog.lines().takeLast(20).joinToString("\n")
                            callback.onError(
                                "FFmpeg code: ${session.returnCode}\n\n$tail"
                            )
                        }
                    },
                    null
                ) { stats: Statistics ->
                    if (durationMs > 0) {
                        val percent = ((stats.time.toFloat() / durationMs.toFloat()) * 100f)
                            .toInt()
                            .coerceIn(0, 100)
                        callback.onProgress(percent)
                    }
                }
            } catch (t: Throwable) {
                callback.onError("Обработка: ${t.javaClass.simpleName}: ${t.message}")
            }
        }.start()
    }

    /**
     * Ищет один наиболее вероятный повторяющийся текстовый watermark.
     * Для известных watermark (включая Dola AI) есть сильный приоритет,
     * но общий скоринг работает и для других коротких повторяющихся оверлеев.
     */
    private fun detectRepeatedTextWatermark(
        inputPath: String,
        durationMs: Long
    ): List<MaskKeyframe>? {
        val retriever = MediaMetadataRetriever()
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        try {
            retriever.setDataSource(inputPath)

            val firstFrame = retriever.getFrameAtTime(
                0L,
                MediaMetadataRetriever.OPTION_CLOSEST
            ) ?: return null

            val videoWidth = firstFrame.width
            val videoHeight = firstFrame.height
            firstFrame.recycle()

            if (videoWidth < 16 || videoHeight < 16) return null

            val stepMs = when {
                durationMs <= 30_000L -> 200L
                durationMs <= 90_000L -> 350L
                else -> 600L
            }

            val times = mutableListOf<Long>()
            var t = 0L
            while (t <= durationMs) {
                times.add(t)
                t += stepMs
            }
            if (times.isEmpty() || times.last() < durationMs) {
                times.add(durationMs)
            }

            val groups = mutableListOf<TextGroup>()

            for (timeMs in times) {
                val frame = retriever.getFrameAtTime(
                    timeMs * 1000L,
                    MediaMetadataRetriever.OPTION_CLOSEST
                ) ?: continue

                val targetWidth = when {
                    frame.width < 1080 -> minOf(1080, (frame.width * 1.7f).toInt())
                    frame.width > 1280 -> 1280
                    else -> frame.width
                }.coerceAtLeast(frame.width)

                val targetHeight = maxOf(
                    1,
                    (frame.height * targetWidth.toFloat() / frame.width.toFloat()).toInt()
                )

                val ocrFrame = if (targetWidth != frame.width) {
                    Bitmap.createScaledBitmap(frame, targetWidth, targetHeight, true)
                } else {
                    frame
                }

                try {
                    val result = Tasks.await(
                        recognizer.process(InputImage.fromBitmap(ocrFrame, 0))
                    )

                    for (block in result.textBlocks) {
                        for (line in block.lines) {
                            val box = line.boundingBox ?: continue
                            val key = normalizeText(line.text)
                            if (key.length !in 2..40) continue
                            if (!isCompactOverlay(box.width(), box.height(), ocrFrame.width, ocrFrame.height)) {
                                continue
                            }

                            val rect = toSafeVideoRect(
                                left = box.left.toFloat(),
                                top = box.top.toFloat(),
                                right = box.right.toFloat(),
                                bottom = box.bottom.toFloat(),
                                ocrWidth = ocrFrame.width,
                                ocrHeight = ocrFrame.height,
                                videoWidth = videoWidth,
                                videoHeight = videoHeight
                            ) ?: continue

                            val hit = TextHit(
                                timeMs = timeMs,
                                key = key,
                                text = line.text,
                                rect = rect
                            )

                            var group = groups.firstOrNull { sameTextKey(it.key, key) }
                            if (group == null) {
                                group = TextGroup(key)
                                groups.add(group)
                            }
                            group.hits.add(hit)
                        }
                    }
                } finally {
                    if (ocrFrame !== frame) ocrFrame.recycle()
                    frame.recycle()
                }
            }

            if (groups.isEmpty()) return null

            data class RankedGroup(
                val group: TextGroup,
                val score: Float,
                val uniqueHits: List<TextHit>
            )

            val ranked = groups.mapNotNull { group ->
                val unique = group.hits
                    .groupBy { it.timeMs }
                    .values
                    .mapNotNull { hits -> hits.minByOrNull { it.rect.width() * it.rect.height() } }
                    .sortedBy { it.timeMs }

                if (unique.size < 2) return@mapNotNull null

                val areas = unique.map { it.rect.width() * it.rect.height() }.sorted()
                val medianArea = areas[areas.size / 2].coerceAtLeast(1f)
                val frameArea = (videoWidth.toFloat() * videoHeight.toFloat()).coerceAtLeast(1f)
                val areaRatio = medianArea / frameArea
                if (areaRatio > 0.04f) return@mapNotNull null

                val minArea = areas.first().coerceAtLeast(1f)
                val maxArea = areas.last().coerceAtLeast(minArea)
                val sizeRatio = maxArea / minArea
                if (sizeRatio > 6.0f) return@mapNotNull null

                var edgeHits = 0
                val bins = mutableSetOf<String>()
                for (hit in unique) {
                    val nx = hit.rect.centerX() / videoWidth.toFloat()
                    val ny = hit.rect.centerY() / videoHeight.toFloat()
                    bins.add("${(nx * 6f).toInt()}:${(ny * 8f).toInt()}")
                    if (nx < 0.25f || nx > 0.75f || ny < 0.20f || ny > 0.80f) {
                        edgeHits++
                    }
                }

                val coverage = unique.size.toFloat() / times.size.toFloat()
                val edgeFraction = edgeHits.toFloat() / unique.size.toFloat()
                val compactBonus = when {
                    areaRatio <= 0.0035f -> 8f
                    areaRatio <= 0.010f -> 4f
                    else -> 1f
                }
                val shortTextBonus = when (group.key.length) {
                    in 3..12 -> 5f
                    in 13..20 -> 2f
                    else -> 0f
                }
                val movingBonus = if (bins.size >= 2) 2f else 0f
                val knownBonus = knownWatermarkBonus(group.key)

                val score =
                    unique.size * 2.6f +
                    coverage * 14f +
                    edgeFraction * 8f +
                    compactBonus +
                    shortTextBonus +
                    movingBonus +
                    knownBonus

                RankedGroup(group, score, unique)
            }.sortedByDescending { it.score }

            val best = ranked.firstOrNull() ?: return null

            // Если OCR нашёл Dola/Doubao/Seedance, такой брендовый сигнал должен
            // выигрывать у текста на футболке даже при меньшем числе распознаваний.
            val known = ranked.firstOrNull { knownWatermarkBonus(it.group.key) > 0f }
            val chosen = if (known != null && known.score >= best.score * 0.55f) known else best

            val sourceHits = chosen.uniqueHits
            if (sourceHits.size < 2) return null

            val maxFillGapMs = stepMs + stepMs / 2L
            val result = mutableListOf<MaskKeyframe>()

            for (timeMs in times) {
                val exact = sourceHits.firstOrNull { it.timeMs == timeMs }
                val nearest = exact ?: sourceHits.minByOrNull {
                    kotlin.math.abs(it.timeMs - timeMs)
                }

                if (nearest != null && kotlin.math.abs(nearest.timeMs - timeMs) <= maxFillGapMs) {
                    result.add(
                        MaskKeyframe(
                            timeMs = timeMs,
                            rect = RectF(nearest.rect),
                            active = true
                        )
                    )
                } else {
                    result.add(
                        MaskKeyframe(
                            timeMs = timeMs,
                            rect = RectF(),
                            active = false
                        )
                    )
                }
            }

            return if (result.count { it.active } >= 2) result else null
        } finally {
            try {
                recognizer.close()
            } catch (_: Throwable) {
            }
            try {
                retriever.release()
            } catch (_: Throwable) {
            }
        }
    }

    private fun isCompactOverlay(
        width: Int,
        height: Int,
        frameWidth: Int,
        frameHeight: Int
    ): Boolean {
        if (width < 7 || height < 5) return false
        if (width > frameWidth * 0.58f || height > frameHeight * 0.18f) return false

        val ratio = (width.toFloat() * height.toFloat()) /
            (frameWidth.toFloat() * frameHeight.toFloat()).coerceAtLeast(1f)

        return ratio in 0.00002f..0.04f
    }

    private fun toSafeVideoRect(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        ocrWidth: Int,
        ocrHeight: Int,
        videoWidth: Int,
        videoHeight: Int
    ): RectF? {
        if (right <= left || bottom <= top) return null

        val boxW = right - left
        val boxH = bottom - top
        val padX = maxOf(10f, boxW * 0.30f)
        val padY = maxOf(8f, boxH * 0.55f)

        val sx = videoWidth.toFloat() / ocrWidth.toFloat()
        val sy = videoHeight.toFloat() / ocrHeight.toFloat()

        val margin = 2f
        val maxRight = videoWidth.toFloat() - margin
        val maxBottom = videoHeight.toFloat() - margin
        if (maxRight <= margin + 2f || maxBottom <= margin + 2f) return null

        val outLeft = ((left - padX) * sx).coerceIn(margin, maxRight - 2f)
        val outTop = ((top - padY) * sy).coerceIn(margin, maxBottom - 2f)
        val outRight = ((right + padX) * sx).coerceIn(outLeft + 2f, maxRight)
        val outBottom = ((bottom + padY) * sy).coerceIn(outTop + 2f, maxBottom)

        if (outRight - outLeft < 2f || outBottom - outTop < 2f) return null
        return RectF(outLeft, outTop, outRight, outBottom)
    }

    private fun normalizeText(value: String): String {
        return value
            .lowercase()
            .filter { it.isLetterOrDigit() }
            .replace('0', 'o')
            .replace('1', 'i')
            .replace('l', 'i')
    }

    private fun knownWatermarkBonus(key: String): Float {
        return when {
            key.contains("dola") || key.contains("doia") -> 80f
            key.contains("doubao") -> 70f
            key.contains("seedance") -> 60f
            key.contains("capcut") -> 45f
            key.contains("tiktok") -> 45f
            else -> 0f
        }
    }

    private fun sameTextKey(a: String, b: String): Boolean {
        if (a == b) return true
        if (a.length >= 3 && b.length >= 3 && (a.contains(b) || b.contains(a))) {
            return true
        }

        val minLen = minOf(a.length, b.length)
        if (minLen < 3) return false
        val allowed = maxOf(1, minLen / 3)
        return editDistance(a, b) <= allowed
    }

    private fun editDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)

        for (i in a.indices) {
            current[0] = i + 1
            for (j in b.indices) {
                val cost = if (a[i] == b[j]) 0 else 1
                current[j + 1] = minOf(
                    current[j] + 1,
                    previous[j + 1] + 1,
                    previous[j] + cost
                )
            }
            val swap = previous
            previous = current
            current = swap
        }

        return previous[b.length]
    }
}
