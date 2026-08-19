package com.akula.watermarkremover

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.SystemClock
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.nio.FloatBuffer
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Быстрый качественный режим удаления watermark.
 *
 * Главное отличие от старого режима:
 * - видео НЕ разбирается на сотни PNG;
 * - LaMa НЕ запускается на каждом кадре;
 * - используется встроенная фиксированная LaMa 512 INT8;
 * - скорость первого inference измеряется на самом телефоне;
 * - количество AI-якорей автоматически подбирается под временной бюджет;
 * - между AI-якорями создаются промежуточные чистые patch-и интерполяцией;
 * - нейросеть получает только локальный контекст вокруг watermark, поэтому
 *   лишняя полноэкранная постобработка больше не съедает память и время.
 */
object NeuralInpainter {

    interface Callback {
        fun onProgress(percent: Int, stage: String)
        fun onSuccess(outputPath: String)
        fun onError(message: String)
    }

    private data class PatchOp(
        val startMs: Long,
        val endMs: Long,
        val rect: RectF,
        val file: File
    )

    private data class AnchorPatch(
        val keyIndex: Int,
        val timeMs: Long,
        val trackId: Int,
        val bitmap: Bitmap
    )

    private const val MODEL_ASSET = "lama_512_int8.onnx"
    private const val MODEL_INPUT_SIZE = 512
    private const val INPUT_NAME = "input"

    // Модель всё равно получает 512x512. Декодировать 4K-кадр целиком для
    // одного маленького watermark бессмысленно, поэтому ограничиваем preview.
    private const val MAX_DECODE_SIDE = 1280

    // Для короткого ролика оставляем примерно 30-35 секунд на сами AI-вызовы,
    // остальное — получение кадров, подготовка patch-ей и финальный encode.
    private const val SHORT_AI_BUDGET_MS = 32_000L
    private const val MEDIUM_AI_BUDGET_MS = 40_000L
    private const val LONG_AI_BUDGET_MS = 50_000L

    private val ortEnv: OrtEnvironment by lazy {
        OrtEnvironment.getEnvironment()
    }

    private val sessionLock = Any()

    @Volatile
    private var cachedSession: OrtSession? = null

    /**
     * Загружаем 60-МБ модель заранее, пока пользователь выбирает и тречит видео.
     * Поэтому загрузка модели не должна попадать в основное время обработки.
     */
    fun warmUp(context: Context) {
        if (cachedSession != null) return
        Thread {
            try {
                getSession(context.applicationContext)
            } catch (_: Throwable) {
                // Основной process покажет точную ошибку, если модель не загрузится.
            }
        }.start()
    }

    fun process(
        context: Context,
        inputPath: String,
        outputPath: String,
        keyframes: List<MaskKeyframe>,
        videoDurationMs: Long,
        callback: Callback
    ) {
        Thread {
            try {
                runPipeline(
                    context = context.applicationContext,
                    inputPath = inputPath,
                    outputPath = outputPath,
                    keyframes = keyframes,
                    videoDurationMs = videoDurationMs,
                    callback = callback
                )
            } catch (t: Throwable) {
                callback.onError("LaMa: ${t.javaClass.simpleName}: ${t.message}")
            }
        }.start()
    }

    private fun runPipeline(
        context: Context,
        inputPath: String,
        outputPath: String,
        keyframes: List<MaskKeyframe>,
        videoDurationMs: Long,
        callback: Callback
    ) {
        val sorted = keyframes.sortedBy { it.timeMs }
        if (sorted.isEmpty()) {
            callback.onError("Нет точек трекинга watermark")
            return
        }

        val activeIndexes = sorted.indices.filter { index ->
            val k = sorted[index]
            k.active && k.rect.width() >= 2f && k.rect.height() >= 2f
        }

        if (activeIndexes.isEmpty()) {
            callback.onProgress(85, "Watermark не найден — копирование видео")
            val copy = FFmpegKit.executeWithArguments(
                arrayOf("-y", "-i", inputPath, "-c", "copy", outputPath)
            )
            if (ReturnCode.isSuccess(copy.returnCode)) {
                callback.onProgress(100, "Готово")
                callback.onSuccess(outputPath)
            } else {
                callback.onError("Не удалось сохранить видео: ${copy.returnCode}")
            }
            return
        }

        callback.onProgress(2, "LaMa INT8 готовится")
        val session = getSession(context)

        val workDir = File(context.cacheDir, "lama_fast_${System.currentTimeMillis()}")
            .apply { mkdirs() }

        val retriever = MediaMetadataRetriever()
        val anchors = ArrayList<AnchorPatch>()

        try {
            retriever.setDataSource(inputPath)

            val metadataW = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
            )?.toIntOrNull()?.coerceAtLeast(1) ?: 1

            val metadataH = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
            )?.toIntOrNull()?.coerceAtLeast(1) ?: 1

            val durationMs = videoDurationMs.coerceAtLeast(1L)
            val decodeSize = calculateDecodeSize(metadataW, metadataH)

            // Первый AI-якорь нужен и для результата, и как реальный benchmark
            // конкретного телефона. После него выбираем число остальных якорей.
            val firstIndex = activeIndexes.first()
            callback.onProgress(4, "Измерение скорости LaMa")

            val firstStart = SystemClock.elapsedRealtime()
            val firstAnchor = createAnchorPatch(
                retriever = retriever,
                session = session,
                keyIndex = firstIndex,
                key = sorted[firstIndex],
                metadataW = metadataW,
                metadataH = metadataH,
                decodeW = decodeSize.first,
                decodeH = decodeSize.second,
                durationMs = durationMs
            ) ?: run {
                callback.onError("Не удалось получить первый кадр для LaMa")
                return
            }
            val firstInferenceMs =
                (SystemClock.elapsedRealtime() - firstStart).coerceAtLeast(1L)
            anchors.add(firstAnchor)

            val selectedAnchorIndexes = chooseAnchorIndexes(
                sorted = sorted,
                activeIndexes = activeIndexes,
                durationMs = durationMs,
                measuredAnchorMs = firstInferenceMs
            )

            val totalAi = selectedAnchorIndexes.size.coerceAtLeast(1)
            val secondsPerAnchor = firstInferenceMs / 1000.0
            callback.onProgress(
                8,
                "LaMa: ${String.format(Locale.US, "%.1f", secondsPerAnchor)}с/AI • план $totalAi точек"
            )

            selectedAnchorIndexes.forEachIndexed { selectedPosition, keyIndex ->
                if (keyIndex == firstIndex) return@forEachIndexed

                val anchor = createAnchorPatch(
                    retriever = retriever,
                    session = session,
                    keyIndex = keyIndex,
                    key = sorted[keyIndex],
                    metadataW = metadataW,
                    metadataH = metadataH,
                    decodeW = decodeSize.first,
                    decodeH = decodeSize.second,
                    durationMs = durationMs
                ) ?: return@forEachIndexed

                anchors.add(anchor)

                val done = anchors.size.coerceAtMost(totalAi)
                val percent = 8 + (done * 52 / totalAi)
                callback.onProgress(
                    percent.coerceIn(8, 60),
                    "LaMa AI: $done/$totalAi"
                )
            }

            anchors.sortBy { it.timeMs }
            if (anchors.isEmpty()) {
                callback.onError("LaMa не создала ни одного чистого patch")
                return
            }

            callback.onProgress(64, "Интерполяция чистого фона")
            val ops = buildInterpolatedPatchOps(
                sorted = sorted,
                activeIndexes = activeIndexes,
                anchors = anchors,
                metadataW = metadataW,
                metadataH = metadataH,
                durationMs = durationMs,
                workDir = workDir,
                callback = callback
            )

            if (ops.isEmpty()) {
                callback.onError("Не удалось построить AI patch-и")
                return
            }

            callback.onProgress(82, "Быстрая сборка видео")
            val ffmpegArgs = buildOverlayCommand(
                inputPath = inputPath,
                outputPath = outputPath,
                ops = ops
            )

            val sessionResult = FFmpegKit.executeWithArguments(ffmpegArgs)
            if (ReturnCode.isSuccess(sessionResult.returnCode)) {
                val output = File(outputPath)
                if (output.exists() && output.length() > 0L) {
                    callback.onProgress(100, "Готово")
                    callback.onSuccess(outputPath)
                } else {
                    callback.onError("FFmpeg завершился без готового файла")
                }
            } else {
                val logs = sessionResult.allLogsAsString ?: ""
                val tail = logs.lines().takeLast(35).joinToString("\n")
                callback.onError(
                    "Сборка LaMa-video: ${sessionResult.returnCode}\n$tail"
                )
            }
        } finally {
            anchors.forEach { anchor ->
                try {
                    if (!anchor.bitmap.isRecycled) anchor.bitmap.recycle()
                } catch (_: Throwable) {
                }
            }
            try { retriever.release() } catch (_: Throwable) {}
            workDir.deleteRecursively()
        }
    }

    /**
     * Количество настоящих запусков LaMa зависит от скорости телефона.
     * На быстром устройстве точек больше, на медленном — меньше.
     * Это намного стабильнее фиксированного "каждый N-й кадр".
     */
    private fun chooseAnchorIndexes(
        sorted: List<MaskKeyframe>,
        activeIndexes: List<Int>,
        durationMs: Long,
        measuredAnchorMs: Long
    ): List<Int> {
        if (activeIndexes.size <= 2) return activeIndexes

        val budgetMs = when {
            durationMs <= 20_000L -> SHORT_AI_BUDGET_MS
            durationMs <= 40_000L -> MEDIUM_AI_BUDGET_MS
            else -> LONG_AI_BUDGET_MS
        }

        val safeMeasured = measuredAnchorMs.coerceAtLeast(350L)
        val byPhoneSpeed = (budgetMs / safeMeasured)
            .toInt()
            .coerceIn(3, 24)

        // Даже на очень быстром телефоне нет смысла запускать LaMa десятки раз
        // в секунду: промежуточный фон строится из соседних AI-якорей.
        val byDuration = (ceil(durationMs / 1_100.0).toInt() + 1)
            .coerceIn(3, 24)

        val totalLimit = min(activeIndexes.size, min(byPhoneSpeed, byDuration))
            .coerceAtLeast(1)

        val runs = splitActiveRuns(sorted, activeIndexes)
        if (runs.size == 1) {
            return evenlySelect(runs.first(), totalLimit)
        }

        val result = LinkedHashSet<Int>()
        var remaining = totalLimit
        var remainingItems = activeIndexes.size

        runs.forEachIndexed { runIndex, run ->
            val runsLeft = runs.size - runIndex
            val minimumForRest = (runsLeft - 1).coerceAtLeast(0)
            val proportional = if (remainingItems > 0) {
                (remaining.toFloat() * run.size / remainingItems).roundToInt()
            } else {
                1
            }
            val quota = proportional
                .coerceAtLeast(1)
                .coerceAtMost((remaining - minimumForRest).coerceAtLeast(1))
                .coerceAtMost(run.size)

            result.addAll(evenlySelect(run, quota))
            remaining -= quota
            remainingItems -= run.size
        }

        // Если округления оставили свободные точки, добавляем их равномерно.
        if (result.size < totalLimit) {
            val leftovers = activeIndexes.filter { it !in result }
            result.addAll(evenlySelect(leftovers, totalLimit - result.size))
        }

        return result.sortedBy { sorted[it].timeMs }
    }

    private fun splitActiveRuns(
        sorted: List<MaskKeyframe>,
        activeIndexes: List<Int>
    ): List<List<Int>> {
        if (activeIndexes.isEmpty()) return emptyList()

        val runs = ArrayList<MutableList<Int>>()
        var current = mutableListOf(activeIndexes.first())
        runs.add(current)

        for (position in 1 until activeIndexes.size) {
            val previousIndex = activeIndexes[position - 1]
            val currentIndex = activeIndexes[position]
            val previous = sorted[previousIndex]
            val item = sorted[currentIndex]

            val sameRun =
                currentIndex == previousIndex + 1 &&
                    previous.active && item.active &&
                    previous.trackId == item.trackId &&
                    item.timeMs - previous.timeMs <= 1_500L

            if (sameRun) {
                current.add(currentIndex)
            } else {
                current = mutableListOf(currentIndex)
                runs.add(current)
            }
        }

        return runs
    }

    private fun evenlySelect(source: List<Int>, count: Int): List<Int> {
        if (source.isEmpty() || count <= 0) return emptyList()
        if (count >= source.size) return source
        if (count == 1) return listOf(source[source.size / 2])

        val result = LinkedHashSet<Int>()
        for (i in 0 until count) {
            val position =
                (i.toDouble() * (source.size - 1).toDouble() / (count - 1).toDouble())
                    .roundToInt()
                    .coerceIn(0, source.lastIndex)
            result.add(source[position])
        }

        // Из-за округления теоретически может получиться на один элемент меньше.
        if (result.size < count) {
            source.forEach { item ->
                if (result.size < count) result.add(item)
            }
        }
        return result.toList()
    }

    private fun createAnchorPatch(
        retriever: MediaMetadataRetriever,
        session: OrtSession,
        keyIndex: Int,
        key: MaskKeyframe,
        metadataW: Int,
        metadataH: Int,
        decodeW: Int,
        decodeH: Int,
        durationMs: Long
    ): AnchorPatch? {
        val frame = getPreviewFrame(
            retriever = retriever,
            timeMs = key.timeMs.coerceIn(0L, durationMs),
            width = decodeW,
            height = decodeH
        ) ?: return null

        try {
            val frameMask = scaleRect(
                key.rect,
                metadataW,
                metadataH,
                frame.width,
                frame.height
            )

            val decodedPatch = inpaintPatch(
                env = ortEnv,
                session = session,
                srcBitmap = frame,
                rawMaskRect = frameMask
            )

            val targetRect = expandedMask(key.rect, metadataW, metadataH)
            val targetW = targetRect.width().toInt().coerceAtLeast(2)
            val targetH = targetRect.height().toInt().coerceAtLeast(2)

            val scaledPatch = if (
                decodedPatch.width != targetW || decodedPatch.height != targetH
            ) {
                Bitmap.createScaledBitmap(decodedPatch, targetW, targetH, true).also {
                    decodedPatch.recycle()
                }
            } else {
                decodedPatch
            }

            return AnchorPatch(
                keyIndex = keyIndex,
                timeMs = key.timeMs,
                trackId = key.trackId,
                bitmap = scaledPatch
            )
        } finally {
            frame.recycle()
        }
    }

    private fun calculateDecodeSize(width: Int, height: Int): Pair<Int, Int> {
        val maxSide = max(width, height)
        if (maxSide <= MAX_DECODE_SIDE) return width to height

        val scale = MAX_DECODE_SIDE.toFloat() / maxSide.toFloat()
        return (
            (width * scale).roundToInt().coerceAtLeast(2)
            ) to (
            (height * scale).roundToInt().coerceAtLeast(2)
            )
    }

    private fun getPreviewFrame(
        retriever: MediaMetadataRetriever,
        timeMs: Long,
        width: Int,
        height: Int
    ): Bitmap? {
        val timeUs = timeMs * 1000L
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            try {
                retriever.getScaledFrameAtTime(
                    timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST,
                    width.coerceAtLeast(2),
                    height.coerceAtLeast(2)
                ) ?: retriever.getFrameAtTime(
                    timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST
                )
            } catch (_: Throwable) {
                retriever.getFrameAtTime(
                    timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST
                )
            }
        } else {
            retriever.getFrameAtTime(
                timeUs,
                MediaMetadataRetriever.OPTION_CLOSEST
            )
        }
    }

    private fun buildInterpolatedPatchOps(
        sorted: List<MaskKeyframe>,
        activeIndexes: List<Int>,
        anchors: List<AnchorPatch>,
        metadataW: Int,
        metadataH: Int,
        durationMs: Long,
        workDir: File,
        callback: Callback
    ): List<PatchOp> {
        val result = ArrayList<PatchOp>()

        activeIndexes.forEachIndexed { position, keyIndex ->
            val key = sorted[keyIndex]
            val targetRect = expandedMask(key.rect, metadataW, metadataH)
            val targetW = targetRect.width().toInt().coerceAtLeast(2)
            val targetH = targetRect.height().toInt().coerceAtLeast(2)

            val sameTrackAnchors = anchors.filter { it.trackId == key.trackId }
            val usableAnchors = if (sameTrackAnchors.isNotEmpty()) {
                sameTrackAnchors
            } else {
                anchors
            }

            val previous = usableAnchors
                .filter { it.timeMs <= key.timeMs }
                .maxByOrNull { it.timeMs }
                ?: usableAnchors.first()

            val next = usableAnchors
                .filter { it.timeMs >= key.timeMs }
                .minByOrNull { it.timeMs }
                ?: usableAnchors.last()

            val patch = interpolateAnchorPatches(
                previous = previous,
                next = next,
                targetTimeMs = key.timeMs,
                targetW = targetW,
                targetH = targetH
            )

            try {
                val patchFile = File(
                    workDir,
                    "patch_${position.toString().padStart(3, '0')}.png"
                )
                patchFile.outputStream().use { out ->
                    if (!patch.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                        throw IllegalStateException("Не удалось сохранить AI patch")
                    }
                }

                val startMs = if (keyIndex == 0) {
                    0L
                } else {
                    midpoint(sorted[keyIndex - 1].timeMs, key.timeMs)
                }.coerceIn(0L, durationMs)

                val endCandidate = if (keyIndex == sorted.lastIndex) {
                    durationMs
                } else {
                    midpoint(key.timeMs, sorted[keyIndex + 1].timeMs)
                }
                val endMs = endCandidate
                    .coerceAtLeast(startMs + 1L)
                    .coerceAtMost(durationMs)

                result.add(
                    PatchOp(
                        startMs = startMs,
                        endMs = endMs,
                        rect = targetRect,
                        file = patchFile
                    )
                )
            } finally {
                patch.recycle()
            }

            val done = position + 1
            val percent = 64 + (done * 14 / activeIndexes.size.coerceAtLeast(1))
            callback.onProgress(
                percent.coerceIn(64, 78),
                "Фон: $done/${activeIndexes.size}"
            )
        }

        return result
    }

    private fun interpolateAnchorPatches(
        previous: AnchorPatch,
        next: AnchorPatch,
        targetTimeMs: Long,
        targetW: Int,
        targetH: Int
    ): Bitmap {
        val prev = if (
            previous.bitmap.width == targetW && previous.bitmap.height == targetH
        ) {
            previous.bitmap
        } else {
            Bitmap.createScaledBitmap(previous.bitmap, targetW, targetH, true)
        }

        if (previous === next || next.timeMs <= previous.timeMs) {
            return prev.copy(Bitmap.Config.ARGB_8888, true).also {
                if (prev !== previous.bitmap) prev.recycle()
            }
        }

        val nxt = if (
            next.bitmap.width == targetW && next.bitmap.height == targetH
        ) {
            next.bitmap
        } else {
            Bitmap.createScaledBitmap(next.bitmap, targetW, targetH, true)
        }

        val alpha = (
            (targetTimeMs - previous.timeMs).toFloat() /
                (next.timeMs - previous.timeMs).toFloat()
            ).coerceIn(0f, 1f)

        val size = targetW * targetH
        val prevPixels = IntArray(size)
        val nextPixels = IntArray(size)
        val outPixels = IntArray(size)

        prev.getPixels(prevPixels, 0, targetW, 0, 0, targetW, targetH)
        nxt.getPixels(nextPixels, 0, targetW, 0, 0, targetW, targetH)

        for (i in 0 until size) {
            outPixels[i] = blend(prevPixels[i], nextPixels[i], alpha)
        }

        if (prev !== previous.bitmap) prev.recycle()
        if (nxt !== next.bitmap) nxt.recycle()

        return Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888).also {
            it.setPixels(outPixels, 0, targetW, 0, 0, targetW, targetH)
        }
    }

    private fun getSession(context: Context): OrtSession {
        cachedSession?.let { return it }

        synchronized(sessionLock) {
            cachedSession?.let { return it }

            val assetNames = context.assets.list("") ?: emptyArray()
            if (MODEL_ASSET !in assetNames) {
                throw IllegalStateException(
                    "В APK нет $MODEL_ASSET. Нужна сборка со встроенной LaMa."
                )
            }

            val modelBytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
            val session = ortEnv.createSession(modelBytes)
            cachedSession = session
            return session
        }
    }

    private fun buildOverlayCommand(
        inputPath: String,
        outputPath: String,
        ops: List<PatchOp>
    ): Array<String> {
        val args = mutableListOf("-y", "-i", inputPath)
        ops.forEach { op ->
            args.add("-i")
            args.add(op.file.absolutePath)
        }

        val filters = ArrayList<String>()
        filters.add("[0:v]setpts=PTS-STARTPTS[v0]")

        ops.forEachIndexed { index, op ->
            val patchInput = index + 1
            val inLabel = "v$index"
            val outLabel = "v${index + 1}"
            val patchLabel = "p$index"

            val x = op.rect.left.toInt().coerceAtLeast(0)
            val y = op.rect.top.toInt().coerceAtLeast(0)
            val t0 = String.format(Locale.US, "%.3f", op.startMs / 1000.0)
            val t1 = String.format(Locale.US, "%.3f", op.endMs / 1000.0)

            filters.add("[$patchInput:v]format=rgba[$patchLabel]")
            filters.add(
                "[$inLabel][$patchLabel]overlay=" +
                    "x=$x:y=$y:" +
                    "enable='between(t,$t0,$t1)':" +
                    "eof_action=repeat:repeatlast=1[$outLabel]"
            )
        }

        args.addAll(
            listOf(
                "-filter_complex", filters.joinToString(";"),
                "-map", "[v${ops.size}]",
                "-map", "0:a:0?",
                "-c:v", "libx264",
                "-preset", "ultrafast",
                "-crf", "18",
                "-pix_fmt", "yuv420p",
                "-movflags", "+faststart",
                "-c:a", "copy",
                outputPath
            )
        )

        return args.toTypedArray()
    }

    private fun midpoint(a: Long, b: Long): Long {
        return a + (b - a) / 2L
    }

    private fun scaleRect(
        rect: RectF,
        sourceW: Int,
        sourceH: Int,
        targetW: Int,
        targetH: Int
    ): RectF {
        val sx = targetW.toFloat() / sourceW.toFloat().coerceAtLeast(1f)
        val sy = targetH.toFloat() / sourceH.toFloat().coerceAtLeast(1f)
        return RectF(
            rect.left * sx,
            rect.top * sy,
            rect.right * sx,
            rect.bottom * sy
        )
    }

    /**
     * LaMa получает квадрат локального контекста вокруг watermark.
     * После inference возвращаем только маленький очищенный patch, а не
     * полноразмерный кадр. Сама нейросеть всё ещё работает на 512x512,
     * поэтому качество модели не урезается.
     */
    private fun inpaintPatch(
        env: OrtEnvironment,
        session: OrtSession,
        srcBitmap: Bitmap,
        rawMaskRect: RectF
    ): Bitmap {
        val frameW = srcBitmap.width
        val frameH = srcBitmap.height
        if (frameW <= 1 || frameH <= 1) {
            return srcBitmap.copy(Bitmap.Config.ARGB_8888, true)
        }

        val targetRect = expandedMask(rawMaskRect, frameW, frameH)
        val contextRect = buildContextRect(targetRect, frameW, frameH)

        val contextLeft = contextRect.left.toInt().coerceIn(0, frameW - 1)
        val contextTop = contextRect.top.toInt().coerceIn(0, frameH - 1)
        val contextRight = contextRect.right.toInt().coerceIn(contextLeft + 1, frameW)
        val contextBottom = contextRect.bottom.toInt().coerceIn(contextTop + 1, frameH)
        val contextW = contextRight - contextLeft
        val contextH = contextBottom - contextTop

        val contextBitmap = Bitmap.createBitmap(
            srcBitmap,
            contextLeft,
            contextTop,
            contextW,
            contextH
        )

        val resized = Bitmap.createScaledBitmap(
            contextBitmap,
            MODEL_INPUT_SIZE,
            MODEL_INPUT_SIZE,
            true
        )

        val maskBitmap = Bitmap.createBitmap(
            MODEL_INPUT_SIZE,
            MODEL_INPUT_SIZE,
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(maskBitmap)
        canvas.drawColor(Color.BLACK)
        val paint = Paint().apply {
            color = Color.WHITE
            isAntiAlias = false
            style = Paint.Style.FILL
        }

        val relativeMask = RectF(
            targetRect.left - contextLeft,
            targetRect.top - contextTop,
            targetRect.right - contextLeft,
            targetRect.bottom - contextTop
        )
        val scaleX = MODEL_INPUT_SIZE / contextW.toFloat()
        val scaleY = MODEL_INPUT_SIZE / contextH.toFloat()
        canvas.drawRect(
            relativeMask.left * scaleX,
            relativeMask.top * scaleY,
            relativeMask.right * scaleX,
            relativeMask.bottom * scaleY,
            paint
        )

        val inputTensor = combinedInputTensor(env, resized, maskBitmap)
        val output512: Bitmap

        try {
            session.run(mapOf(INPUT_NAME to inputTensor)).use { result ->
                val outputTensor = result[0] as OnnxTensor
                output512 = tensorToBitmap(
                    outputTensor,
                    MODEL_INPUT_SIZE,
                    MODEL_INPUT_SIZE
                )
            }
        } finally {
            inputTensor.close()
            resized.recycle()
            maskBitmap.recycle()
        }

        val generatedContext = Bitmap.createScaledBitmap(
            output512,
            contextW,
            contextH,
            true
        )
        output512.recycle()

        val targetLeft = (targetRect.left - contextLeft)
            .toInt()
            .coerceIn(0, contextW - 1)
        val targetTop = (targetRect.top - contextTop)
            .toInt()
            .coerceIn(0, contextH - 1)
        val targetRight = (targetRect.right - contextLeft)
            .toInt()
            .coerceIn(targetLeft + 1, contextW)
        val targetBottom = (targetRect.bottom - contextTop)
            .toInt()
            .coerceIn(targetTop + 1, contextH)
        val patchW = targetRight - targetLeft
        val patchH = targetBottom - targetTop

        val generatedPatch = Bitmap.createBitmap(
            generatedContext,
            targetLeft,
            targetTop,
            patchW,
            patchH
        )
        val originalPatch = Bitmap.createBitmap(
            contextBitmap,
            targetLeft,
            targetTop,
            patchW,
            patchH
        )

        generatedContext.recycle()
        contextBitmap.recycle()

        val output = featherPatch(originalPatch, generatedPatch)
        originalPatch.recycle()
        generatedPatch.recycle()
        return output
    }

    private fun buildContextRect(mask: RectF, width: Int, height: Int): RectF {
        val desiredSide = max(
            256f,
            max(mask.width() * 3.2f, mask.height() * 6.0f)
        ).coerceAtMost(min(width, height).toFloat())

        var left = mask.centerX() - desiredSide / 2f
        var top = mask.centerY() - desiredSide / 2f
        var right = left + desiredSide
        var bottom = top + desiredSide

        if (left < 0f) {
            right -= left
            left = 0f
        }
        if (top < 0f) {
            bottom -= top
            top = 0f
        }
        if (right > width) {
            val shift = right - width
            left -= shift
            right = width.toFloat()
        }
        if (bottom > height) {
            val shift = bottom - height
            top -= shift
            bottom = height.toFloat()
        }

        left = left.coerceAtLeast(0f)
        top = top.coerceAtLeast(0f)
        right = right.coerceAtMost(width.toFloat())
        bottom = bottom.coerceAtMost(height.toFloat())

        return RectF(left, top, right, bottom)
    }

    private fun featherPatch(original: Bitmap, generated: Bitmap): Bitmap {
        val width = original.width
        val height = original.height
        val size = width * height
        val originalPixels = IntArray(size)
        val generatedPixels = IntArray(size)
        val outPixels = IntArray(size)

        original.getPixels(originalPixels, 0, width, 0, 0, width, height)
        generated.getPixels(generatedPixels, 0, width, 0, 0, width, height)

        val feather = max(3f, min(width, height) * 0.08f)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val edge = minOf(
                    x.toFloat(),
                    (width - 1 - x).toFloat(),
                    y.toFloat(),
                    (height - 1 - y).toFloat()
                )
                val alpha = (edge / feather).coerceIn(0f, 1f)
                val index = y * width + x
                outPixels[index] = blend(
                    originalPixels[index],
                    generatedPixels[index],
                    alpha
                )
            }
        }

        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            it.setPixels(outPixels, 0, width, 0, 0, width, height)
        }
    }

    /**
     * g-ronimo/lama_512_int8: [1,4,512,512].
     * RGB 0..1 с нулями под маской + бинарный mask-канал.
     */
    private fun combinedInputTensor(
        env: OrtEnvironment,
        bitmap: Bitmap,
        maskBitmap: Bitmap
    ): OnnxTensor {
        val size = bitmap.width * bitmap.height
        val imagePixels = IntArray(size)
        val maskPixels = IntArray(size)

        bitmap.getPixels(
            imagePixels,
            0,
            bitmap.width,
            0,
            0,
            bitmap.width,
            bitmap.height
        )
        maskBitmap.getPixels(
            maskPixels,
            0,
            maskBitmap.width,
            0,
            0,
            maskBitmap.width,
            maskBitmap.height
        )

        val buffer = FloatBuffer.allocate(4 * size)

        for (channel in 0 until 3) {
            for (i in 0 until size) {
                val masked = Color.red(maskPixels[i]) > 127
                val value = if (masked) {
                    0f
                } else {
                    when (channel) {
                        0 -> Color.red(imagePixels[i]) / 255f
                        1 -> Color.green(imagePixels[i]) / 255f
                        else -> Color.blue(imagePixels[i]) / 255f
                    }
                }
                buffer.put(channel * size + i, value)
            }
        }

        for (i in 0 until size) {
            buffer.put(
                3 * size + i,
                if (Color.red(maskPixels[i]) > 127) 1f else 0f
            )
        }

        buffer.rewind()
        return OnnxTensor.createTensor(
            env,
            buffer,
            longArrayOf(1, 4, MODEL_INPUT_SIZE.toLong(), MODEL_INPUT_SIZE.toLong())
        )
    }

    private fun expandedMask(rect: RectF, width: Int, height: Int): RectF {
        val safeWidth = width.coerceAtLeast(3)
        val safeHeight = height.coerceAtLeast(3)
        val padX = maxOf(4f, rect.width() * 0.08f)
        val padY = maxOf(4f, rect.height() * 0.12f)

        val left = (rect.left - padX).coerceIn(0f, safeWidth.toFloat() - 2f)
        val top = (rect.top - padY).coerceIn(0f, safeHeight.toFloat() - 2f)
        val right = (rect.right + padX).coerceIn(left + 2f, safeWidth.toFloat())
        val bottom = (rect.bottom + padY).coerceIn(top + 2f, safeHeight.toFloat())

        return RectF(left, top, right, bottom)
    }

    private fun blend(a: Int, b: Int, alpha: Float): Int {
        if (alpha <= 0f) return a
        if (alpha >= 1f) return b

        val inv = 1f - alpha
        return Color.rgb(
            (Color.red(a) * inv + Color.red(b) * alpha).toInt().coerceIn(0, 255),
            (Color.green(a) * inv + Color.green(b) * alpha).toInt().coerceIn(0, 255),
            (Color.blue(a) * inv + Color.blue(b) * alpha).toInt().coerceIn(0, 255)
        )
    }

    private fun tensorToBitmap(tensor: OnnxTensor, width: Int, height: Int): Bitmap {
        val buffer = tensor.floatBuffer
        buffer.rewind()

        val size = width * height
        val pixels = IntArray(size)

        for (i in 0 until size) {
            val r = (buffer.get(i) * 255f).toInt().coerceIn(0, 255)
            val g = (buffer.get(size + i) * 255f).toInt().coerceIn(0, 255)
            val b = (buffer.get(2 * size + i) * 255f).toInt().coerceIn(0, 255)
            pixels[i] = Color.rgb(r, g, b)
        }

        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            it.setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }
}
