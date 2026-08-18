package com.akula.watermarkremover

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Быстрый режим удаления watermark.
 *
 * Основной путь теперь использует FFmpeg removelogo по временным маскам. В отличие
 * от прямоугольного delogo этот фильтр использует окружающие незамаскированные
 * пиксели как источник восстановления и обычно оставляет заметно меньше полос.
 * Старый delogo сохранён только как аварийный fallback.
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

    private data class LogoRun(
        var startMs: Long,
        var endMs: Long,
        val rect: RectF
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
                callback.onProgress(1)

                val verified = detectRepeatedTextWatermark(
                    inputPath = inputPath,
                    durationMs = durationMs,
                    callback = callback
                )
                val processingKeyframes = verified ?: keyframes

                if (processingKeyframes.none {
                        it.active && it.rect.width() >= 2f && it.rect.height() >= 2f
                    }) {
                    callback.onError("Не найдено ни одной рабочей области watermark")
                    return@Thread
                }

                val videoSize = readVideoSize(inputPath)
                if (videoSize == null) {
                    callback.onError("Не удалось определить размер видео")
                    return@Thread
                }

                val runs = buildLogoRuns(
                    keyframes = processingKeyframes,
                    durationMs = durationMs,
                    videoWidth = videoSize.first,
                    videoHeight = videoSize.second
                )

                if (runs.isEmpty()) {
                    callback.onError("Не удалось построить маску watermark")
                    return@Thread
                }

                callback.onProgress(38)

                val maskDir = File(
                    File(outputPath).parentFile ?: File(inputPath).parentFile,
                    "wm_masks_${System.nanoTime()}"
                )
                maskDir.mkdirs()

                val maskFiles = ArrayList<File>()
                val filterParts = ArrayList<String>()
                filterParts.add("format=yuv420p")

                for ((index, run) in runs.withIndex()) {
                    val maskFile = File(maskDir, "mask_$index.png")
                    createMaskPng(
                        file = maskFile,
                        width = videoSize.first,
                        height = videoSize.second,
                        rect = run.rect
                    )
                    maskFiles.add(maskFile)

                    val start = seconds(run.startMs)
                    val end = seconds(run.endMs)
                    val path = escapeFilterPath(maskFile.absolutePath)
                    filterParts.add(
                        "removelogo=filename=$path:enable='between(t,$start,$end)'"
                    )
                }

                filterParts.add("scale=-2:1080")
                val removelogoFilter = filterParts.joinToString(",")

                executePrimaryRemoval(
                    inputPath = inputPath,
                    outputPath = outputPath,
                    durationMs = durationMs,
                    filter = removelogoFilter,
                    keyframes = processingKeyframes,
                    callback = callback,
                    cleanup = {
                        for (file in maskFiles) {
                            try { file.delete() } catch (_: Throwable) {}
                        }
                        try { maskDir.delete() } catch (_: Throwable) {}
                    }
                )
            } catch (t: Throwable) {
                callback.onError("Обработка: ${t.javaClass.simpleName}: ${t.message}")
            }
        }.start()
    }

    private fun executePrimaryRemoval(
        inputPath: String,
        outputPath: String,
        durationMs: Long,
        filter: String,
        keyframes: List<MaskKeyframe>,
        callback: Callback,
        cleanup: () -> Unit
    ) {
        val cmd = buildFfmpegCommand(inputPath, outputPath, filter)

        FFmpegKit.executeWithArgumentsAsync(
            cmd,
            { session: FFmpegSession ->
                if (ReturnCode.isSuccess(session.returnCode)) {
                    cleanup()
                    finishSuccess(outputPath, callback)
                } else {
                    // На редких сборках removelogo может быть отсутствующим или не принять
                    // конкретную маску. Тогда не оставляем пользователя без результата:
                    // один раз запускаем проверенный delogo fallback.
                    try { File(outputPath).delete() } catch (_: Throwable) {}
                    cleanup()
                    executeLegacyFallback(
                        inputPath = inputPath,
                        outputPath = outputPath,
                        durationMs = durationMs,
                        keyframes = keyframes,
                        callback = callback,
                        primaryLog = session.allLogsAsString ?: ""
                    )
                }
            },
            null
        ) { stats: Statistics ->
            if (durationMs > 0) {
                val ffmpegPercent = ((stats.time.toFloat() / durationMs.toFloat()) * 100f)
                    .toInt()
                    .coerceIn(0, 100)
                val totalPercent = 40 + (ffmpegPercent * 60 / 100)
                callback.onProgress(totalPercent.coerceIn(40, 99))
            }
        }
    }

    private fun executeLegacyFallback(
        inputPath: String,
        outputPath: String,
        durationMs: Long,
        keyframes: List<MaskKeyframe>,
        callback: Callback,
        primaryLog: String
    ) {
        val delogoChain = MaskTracker.buildTrackedDelogoFilter(keyframes, durationMs)
        if (delogoChain.isBlank() || delogoChain == "null") {
            callback.onError("removelogo не запустился, а fallback не получил маску")
            return
        }

        val filter = "$delogoChain,scale=-2:1080"
        val cmd = buildFfmpegCommand(inputPath, outputPath, filter)

        FFmpegKit.executeWithArgumentsAsync(
            cmd,
            { session: FFmpegSession ->
                if (ReturnCode.isSuccess(session.returnCode)) {
                    finishSuccess(outputPath, callback)
                } else {
                    val secondaryLog = session.allLogsAsString ?: ""
                    val combined = (primaryLog + "\n" + secondaryLog)
                        .lines()
                        .takeLast(24)
                        .joinToString("\n")
                    callback.onError(
                        "Оба способа удаления завершились ошибкой.\n\n$combined"
                    )
                }
            },
            null
        ) { stats: Statistics ->
            if (durationMs > 0) {
                val ffmpegPercent = ((stats.time.toFloat() / durationMs.toFloat()) * 100f)
                    .toInt()
                    .coerceIn(0, 100)
                val totalPercent = 40 + (ffmpegPercent * 60 / 100)
                callback.onProgress(totalPercent.coerceIn(40, 99))
            }
        }
    }

    private fun buildFfmpegCommand(
        inputPath: String,
        outputPath: String,
        filter: String
    ): Array<String> {
        return arrayOf(
            "-y",
            "-i", inputPath,
            "-vf", filter,
            "-c:v", "libx264",
            "-preset", "medium",
            "-crf", "20",
            "-c:a", "copy",
            outputPath
        )
    }

    private fun finishSuccess(outputPath: String, callback: Callback) {
        val output = File(outputPath)
        if (output.exists() && output.length() > 0L) {
            callback.onProgress(100)
            callback.onSuccess(outputPath)
        } else {
            callback.onError("FFmpeg завершился без готового видеофайла")
        }
    }

    private fun readVideoSize(inputPath: String): Pair<Int, Int>? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(inputPath)
            val frame = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST)
                ?: return null
            try {
                frame.width to frame.height
            } finally {
                frame.recycle()
            }
        } catch (_: Throwable) {
            null
        } finally {
            try { retriever.release() } catch (_: Throwable) {}
        }
    }

    private fun buildLogoRuns(
        keyframes: List<MaskKeyframe>,
        durationMs: Long,
        videoWidth: Int,
        videoHeight: Int
    ): List<LogoRun> {
        val sorted = keyframes.sortedBy { it.timeMs }
        if (sorted.isEmpty()) return emptyList()

        val totalMs = durationMs.coerceAtLeast(1L)
        val stepMs = 250L
        val runs = ArrayList<LogoRun>()
        var current: LogoRun? = null
        var t0 = 0L

        while (t0 < totalMs) {
            val t1 = (t0 + stepMs).coerceAtMost(totalMs)
            val mid = (t0 + t1) / 2L
            val state = nearestKeyframe(sorted, mid)

            if (state.active && state.rect.width() >= 2f && state.rect.height() >= 2f) {
                val expanded = expandRepairRect(
                    state.rect,
                    videoWidth,
                    videoHeight
                )

                if (current != null &&
                    current.endMs == t0 &&
                    nearlySameRect(current.rect, expanded)
                ) {
                    current.endMs = t1
                } else {
                    current = LogoRun(t0, t1, expanded)
                    runs.add(current)
                }
            } else {
                current = null
            }

            t0 = t1
        }

        return runs
    }

    private fun nearestKeyframe(
        sorted: List<MaskKeyframe>,
        timeMs: Long
    ): MaskKeyframe {
        if (sorted.size == 1) return sorted[0]
        if (timeMs <= sorted.first().timeMs) return sorted.first()
        if (timeMs >= sorted.last().timeMs) return sorted.last()

        var best = sorted.first()
        var distance = kotlin.math.abs(best.timeMs - timeMs)
        for (item in sorted) {
            val d = kotlin.math.abs(item.timeMs - timeMs)
            if (d < distance) {
                best = item
                distance = d
            }
        }
        return best
    }

    private fun expandRepairRect(
        source: RectF,
        videoWidth: Int,
        videoHeight: Int
    ): RectF {
        // OCR обычно ограничивает рамку самими видимыми буквами, а у прозрачного
        // watermark остаётся полупрозрачный ореол. Дополнительный запас закрывает
        // этот ореол, но остаётся умеренным, чтобы не портить фон вокруг.
        val padX = maxOf(7f, source.width() * 0.18f)
        val padY = maxOf(5f, source.height() * 0.30f)
        val margin = 2f

        return RectF(
            (source.left - padX).coerceIn(margin, videoWidth.toFloat() - margin - 2f),
            (source.top - padY).coerceIn(margin, videoHeight.toFloat() - margin - 2f),
            (source.right + padX).coerceIn(margin + 2f, videoWidth.toFloat() - margin),
            (source.bottom + padY).coerceIn(margin + 2f, videoHeight.toFloat() - margin)
        )
    }

    private fun nearlySameRect(a: RectF, b: RectF): Boolean {
        val tolerance = 6f
        return kotlin.math.abs(a.left - b.left) <= tolerance &&
            kotlin.math.abs(a.top - b.top) <= tolerance &&
            kotlin.math.abs(a.right - b.right) <= tolerance &&
            kotlin.math.abs(a.bottom - b.bottom) <= tolerance
    }

    private fun createMaskPng(
        file: File,
        width: Int,
        height: Int,
        rect: RectF
    ) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.BLACK)

            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.FILL
            }

            val radius = minOf(rect.width(), rect.height()) * 0.28f
            canvas.drawRoundRect(rect, radius, radius, paint)

            file.outputStream().use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    throw IllegalStateException("Не удалось создать mask PNG")
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun escapeFilterPath(path: String): String {
        return path
            .replace("\\", "\\\\")
            .replace(":", "\\:")
            .replace("'", "\\'")
    }

    private fun seconds(timeMs: Long): String {
        return String.format(Locale.US, "%.3f", timeMs / 1000.0)
    }

    /**
     * Ищет один наиболее вероятный повторяющийся текстовый watermark.
     * Для известных watermark (включая Dola AI) есть сильный приоритет,
     * но общий скоринг работает и для других коротких повторяющихся оверлеев.
     */
    private fun detectRepeatedTextWatermark(
        inputPath: String,
        durationMs: Long,
        callback: Callback
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
                durationMs <= 30_000L -> 450L
                durationMs <= 90_000L -> 700L
                else -> 1000L
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

            for ((index, timeMs) in times.withIndex()) {
                val frame = retriever.getFrameAtTime(
                    timeMs * 1000L,
                    MediaMetadataRetriever.OPTION_CLOSEST
                )

                if (frame == null) {
                    val verifyProgress = 5 + (((index + 1).toFloat() / times.size.toFloat()) * 30f).toInt()
                    callback.onProgress(verifyProgress.coerceIn(5, 35))
                    continue
                }

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
                    val result = try {
                        Tasks.await(
                            recognizer.process(InputImage.fromBitmap(ocrFrame, 0)),
                            4L,
                            TimeUnit.SECONDS
                        )
                    } catch (_: Throwable) {
                        null
                    }

                    if (result != null) {
                        for (block in result.textBlocks) {
                            for (line in block.lines) {
                                val box = line.boundingBox ?: continue
                                val key = normalizeText(line.text)
                                if (key.length !in 2..40) continue
                                if (!isCompactOverlay(
                                        box.width(),
                                        box.height(),
                                        ocrFrame.width,
                                        ocrFrame.height
                                    )
                                ) {
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
                    }
                } finally {
                    if (ocrFrame !== frame) ocrFrame.recycle()
                    frame.recycle()

                    val verifyProgress = 5 + (((index + 1).toFloat() / times.size.toFloat()) * 30f).toInt()
                    callback.onProgress(verifyProgress.coerceIn(5, 35))
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
