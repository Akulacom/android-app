package com.akula.watermarkremover

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.RectF
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.nio.FloatBuffer
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Качественный LaMa-режим для видео.
 *
 * Принципиально НЕ переносит и НЕ интерполирует готовый фон между кадрами.
 * Каждый кадр, где watermark активен, обрабатывается независимо на основе
 * пикселей именно этого кадра. Поэтому движущийся фон не превращается в
 * прямоугольные "заплатки" из старого кадра.
 *
 * Для скорости используется фиксированная LaMa 512 INT8. Нейросеть получает
 * только квадрат локального контекста вокруг текущей маски, но запускается
 * для каждого активного видеокадра. Неактивные кадры копируются без декода.
 */
object NeuralInpainter {

    interface Callback {
        fun onProgress(percent: Int, stage: String)
        fun onSuccess(outputPath: String)
        fun onError(message: String)
    }

    private const val MODEL_ASSET = "lama_512_int8.onnx"
    private const val MODEL_INPUT_SIZE = 512
    private const val INPUT_NAME = "input"

    private val ortEnv: OrtEnvironment by lazy {
        OrtEnvironment.getEnvironment()
    }

    private val sessionLock = Any()

    @Volatile
    private var cachedSession: OrtSession? = null

    /**
     * Загружает модель заранее, чтобы создание ORT-сессии не добавляло задержку
     * после нажатия "Применить".
     */
    fun warmUp(context: Context) {
        if (cachedSession != null) return
        Thread {
            try {
                getSession(context.applicationContext)
            } catch (_: Throwable) {
                // process() покажет пользователю точную ошибку, если модель недоступна.
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
        val sortedKeyframes = keyframes.sortedBy { it.timeMs }
        if (sortedKeyframes.isEmpty()) {
            callback.onError("Нет точек трекинга watermark")
            return
        }

        val inputFile = File(inputPath)
        if (!inputFile.exists() || inputFile.length() <= 0L) {
            callback.onError("Исходное видео не найдено")
            return
        }

        val durationMs = videoDurationMs.coerceAtLeast(1L)
        val workDir = File(context.cacheDir, "lama_frames_${System.currentTimeMillis()}")
        val framesDir = File(workDir, "frames")
        val outFramesDir = File(workDir, "frames_out")
        framesDir.mkdirs()
        outFramesDir.mkdirs()

        try {
            callback.onProgress(2, "Подготовка LaMa INT8")
            val session = getSession(context)

            callback.onProgress(4, "Извлечение кадров")
            val extractArgs = arrayOf(
                "-y",
                "-i", inputPath,
                "-fps_mode", "passthrough",
                "${framesDir.absolutePath}/frame_%06d.png"
            )
            val extract = FFmpegKit.executeWithArguments(extractArgs)
            if (!ReturnCode.isSuccess(extract.returnCode)) {
                val tail = extract.allLogsAsString
                    ?.lines()
                    ?.takeLast(30)
                    ?.joinToString("\n")
                    .orEmpty()
                callback.onError(
                    "Не удалось извлечь кадры: ${extract.returnCode}\n$tail"
                )
                return
            }

            val frameFiles = framesDir
                .listFiles { file -> file.isFile && file.name.endsWith(".png") }
                ?.sortedBy { it.name }
                ?: emptyList()

            if (frameFiles.isEmpty()) {
                callback.onError("FFmpeg не извлёк ни одного кадра")
                return
            }

            val totalFrames = frameFiles.size
            val masks = ArrayList<RectF>(totalFrames)
            var activeFrames = 0

            for (index in 0 until totalFrames) {
                val timeMs = frameTimeMs(index, totalFrames, durationMs)
                val mask = maskAtTime(sortedKeyframes, timeMs)
                masks.add(mask)
                if (isActiveMask(mask)) activeFrames++
            }

            if (activeFrames == 0) {
                callback.onProgress(90, "Watermark не найден — копирование видео")
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

            var aiDone = 0
            for (index in frameFiles.indices) {
                val sourceFile = frameFiles[index]
                val outputFile = File(outFramesDir, sourceFile.name)
                val mask = masks[index]

                if (!isActiveMask(mask)) {
                    sourceFile.copyTo(outputFile, overwrite = true)
                } else {
                    val sourceBitmap = BitmapFactory.decodeFile(sourceFile.absolutePath)
                        ?: throw IllegalStateException(
                            "Не удалось открыть кадр ${index + 1}/$totalFrames"
                        )

                    val resultBitmap = try {
                        inpaintCurrentFrame(
                            env = ortEnv,
                            session = session,
                            source = sourceBitmap,
                            rawMask = mask
                        )
                    } finally {
                        if (!sourceBitmap.isRecycled) sourceBitmap.recycle()
                    }

                    try {
                        outputFile.outputStream().use { output ->
                            if (!resultBitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                                throw IllegalStateException(
                                    "Не удалось сохранить кадр ${index + 1}/$totalFrames"
                                )
                            }
                        }
                    } finally {
                        if (!resultBitmap.isRecycled) resultBitmap.recycle()
                    }

                    aiDone++
                }

                val percent = 7 + ((index + 1) * 83 / totalFrames)
                callback.onProgress(
                    percent.coerceIn(7, 90),
                    "LaMa INT8: $aiDone/$activeFrames AI • кадр ${index + 1}/$totalFrames"
                )
            }

            val fps = totalFrames * 1000.0 / durationMs.toDouble()
            callback.onProgress(92, "Сборка видео ${String.format(Locale.US, "%.3f", fps)} fps")

            val assembled = assembleVideo(
                framePattern = "${outFramesDir.absolutePath}/frame_%06d.png",
                inputPath = inputPath,
                outputPath = outputPath,
                fps = fps
            )

            if (assembled.first) {
                val output = File(outputPath)
                if (output.exists() && output.length() > 0L) {
                    callback.onProgress(100, "Готово")
                    callback.onSuccess(outputPath)
                } else {
                    callback.onError("Сборка завершилась без итогового файла")
                }
            } else {
                callback.onError("Не удалось собрать видео\n${assembled.second}")
            }
        } finally {
            workDir.deleteRecursively()
        }
    }

    /**
     * Первый проход сохраняет аудио без перекодирования. Если конкретный аудио-
     * кодек нельзя положить в MP4 через copy, повторяем с AAC вместо падения.
     */
    private fun assembleVideo(
        framePattern: String,
        inputPath: String,
        outputPath: String,
        fps: Double
    ): Pair<Boolean, String> {
        val fpsText = String.format(Locale.US, "%.6f", fps.coerceIn(1.0, 240.0))

        fun args(audioCopy: Boolean): Array<String> {
            val list = mutableListOf(
                "-y",
                "-framerate", fpsText,
                "-i", framePattern,
                "-i", inputPath,
                "-map", "0:v:0",
                "-map", "1:a:0?",
                "-c:v", "libx264",
                "-preset", "veryfast",
                "-crf", "18",
                "-pix_fmt", "yuv420p",
                "-movflags", "+faststart"
            )
            if (audioCopy) {
                list.addAll(listOf("-c:a", "copy"))
            } else {
                list.addAll(listOf("-c:a", "aac", "-b:a", "192k"))
            }
            list.addAll(listOf("-shortest", outputPath))
            return list.toTypedArray()
        }

        val first = FFmpegKit.executeWithArguments(args(audioCopy = true))
        if (ReturnCode.isSuccess(first.returnCode)) return true to ""

        try { File(outputPath).delete() } catch (_: Throwable) {}

        val second = FFmpegKit.executeWithArguments(args(audioCopy = false))
        if (ReturnCode.isSuccess(second.returnCode)) return true to ""

        val tail = second.allLogsAsString
            ?.lines()
            ?.takeLast(35)
            ?.joinToString("\n")
            .orEmpty()
        return false to "FFmpeg ${second.returnCode}\n$tail"
    }

    /**
     * Возвращает маску ближайшего состояния трекера без повторной сортировки
     * списка для каждого из сотен кадров.
     */
    private fun maskAtTime(sorted: List<MaskKeyframe>, timeMs: Long): RectF {
        if (sorted.isEmpty()) return RectF()
        if (sorted.size == 1) {
            val only = sorted[0]
            return if (only.active) RectF(only.rect) else RectF()
        }
        if (timeMs <= sorted.first().timeMs) {
            val first = sorted.first()
            return if (first.active) RectF(first.rect) else RectF()
        }
        if (timeMs >= sorted.last().timeMs) {
            val last = sorted.last()
            return if (last.active) RectF(last.rect) else RectF()
        }

        var best = sorted.first()
        var bestDistance = kotlin.math.abs(best.timeMs - timeMs)
        for (item in sorted) {
            val distance = kotlin.math.abs(item.timeMs - timeMs)
            if (distance < bestDistance) {
                best = item
                bestDistance = distance
            }
        }

        return if (best.active) RectF(best.rect) else RectF()
    }

    private fun frameTimeMs(index: Int, totalFrames: Int, durationMs: Long): Long {
        return if (totalFrames > 1) {
            index.toLong() * durationMs / (totalFrames - 1).toLong()
        } else {
            0L
        }
    }

    private fun isActiveMask(mask: RectF): Boolean {
        return mask.width() >= 2f && mask.height() >= 2f
    }

    /**
     * Обрабатывает текущий видеокадр независимо от соседних кадров.
     * Вне expandedMask результат остаётся пиксельно исходным Bitmap-кадру.
     */
    private fun inpaintCurrentFrame(
        env: OrtEnvironment,
        session: OrtSession,
        source: Bitmap,
        rawMask: RectF
    ): Bitmap {
        val frameW = source.width
        val frameH = source.height
        if (frameW <= 2 || frameH <= 2) {
            return source.copy(Bitmap.Config.ARGB_8888, true)
        }

        val target = expandedMask(rawMask, frameW, frameH)
        val context = buildContextRect(target, frameW, frameH)

        val contextLeft = context.left.roundToInt().coerceIn(0, frameW - 2)
        val contextTop = context.top.roundToInt().coerceIn(0, frameH - 2)
        val contextRight = context.right.roundToInt().coerceIn(contextLeft + 2, frameW)
        val contextBottom = context.bottom.roundToInt().coerceIn(contextTop + 2, frameH)
        val contextW = contextRight - contextLeft
        val contextH = contextBottom - contextTop

        val contextBitmap = Bitmap.createBitmap(
            source,
            contextLeft,
            contextTop,
            contextW,
            contextH
        )

        // Android may return the source Bitmap when no scaling is required.
// Keep an independent object so recycle() cannot invalidate contextBitmap/source.
val resized = if (
    contextBitmap.width == MODEL_INPUT_SIZE &&
    contextBitmap.height == MODEL_INPUT_SIZE
) {
    contextBitmap.copy(Bitmap.Config.ARGB_8888, false)
} else {
    Bitmap.createScaledBitmap(
        contextBitmap,
        MODEL_INPUT_SIZE,
        MODEL_INPUT_SIZE,
        true
    )
}

        val relativeMask = RectF(
            target.left - contextLeft,
            target.top - contextTop,
            target.right - contextLeft,
            target.bottom - contextTop
        )

        val mask512 = RectF(
            relativeMask.left * MODEL_INPUT_SIZE / contextW.toFloat(),
            relativeMask.top * MODEL_INPUT_SIZE / contextH.toFloat(),
            relativeMask.right * MODEL_INPUT_SIZE / contextW.toFloat(),
            relativeMask.bottom * MODEL_INPUT_SIZE / contextH.toFloat()
        )

        val inputTensor = combinedInputTensor(env, resized, mask512)
        val generated512: Bitmap

        try {
            session.run(mapOf(INPUT_NAME to inputTensor)).use { result ->
                val outputTensor = result[0] as OnnxTensor
                generated512 = tensorToBitmap(
                    outputTensor,
                    MODEL_INPUT_SIZE,
                    MODEL_INPUT_SIZE
                )
            }
        } finally {
            inputTensor.close()
            resized.recycle()
        }

        // Same ownership rule for model output: if context is exactly 512x512,
// createScaledBitmap may return generated512 itself.
val generatedContext = if (
    generated512.width == contextW && generated512.height == contextH
) {
    generated512.copy(Bitmap.Config.ARGB_8888, false)
} else {
    Bitmap.createScaledBitmap(
        generated512,
        contextW,
        contextH,
        true
    )
}
generated512.recycle()

        val output = source.copy(Bitmap.Config.ARGB_8888, true)

        try {
            compositeGeneratedRegion(
                output = output,
                generatedContext = generatedContext,
                target = target,
                contextLeft = contextLeft,
                contextTop = contextTop
            )
        } finally {
            generatedContext.recycle()
            contextBitmap.recycle()
        }

        return output
    }

    /**
     * Меняет только target-область текущего кадра. На внешней границе alpha=0,
     * затем smoothstep быстро выходит к 1.0. Padding target-а лежит за буквами
     * watermark, поэтому сами буквы оказываются в полностью AI-заполненной зоне.
     */
    private fun compositeGeneratedRegion(
        output: Bitmap,
        generatedContext: Bitmap,
        target: RectF,
        contextLeft: Int,
        contextTop: Int
    ) {
        val frameW = output.width
        val frameH = output.height

        val left = target.left.toInt().coerceIn(0, frameW - 1)
        val top = target.top.toInt().coerceIn(0, frameH - 1)
        val right = target.right.toInt().coerceIn(left + 1, frameW)
        val bottom = target.bottom.toInt().coerceIn(top + 1, frameH)
        val patchW = right - left
        val patchH = bottom - top

        val originalPixels = IntArray(patchW * patchH)
        output.getPixels(
            originalPixels,
            0,
            patchW,
            left,
            top,
            patchW,
            patchH
        )

        val generatedPixels = IntArray(patchW * patchH)
        val maxSourceX = (generatedContext.width - patchW).coerceAtLeast(0)
        val maxSourceY = (generatedContext.height - patchH).coerceAtLeast(0)
        val sourceX = (left - contextLeft).coerceIn(0, maxSourceX)
        val sourceY = (top - contextTop).coerceIn(0, maxSourceY)

        generatedContext.getPixels(
            generatedPixels,
            0,
            patchW,
            sourceX,
            sourceY,
            patchW,
            patchH
        )

        // Не делаем огромную полупрозрачную рамку. Достаточно мягких 4–12 px,
        // так как сама область уже расширена за реальные края watermark.
        val feather = max(
            4f,
            min(12f, min(patchW, patchH) * 0.12f)
        )

        for (y in 0 until patchH) {
            for (x in 0 until patchW) {
                val edgeDistance = minOf(
                    x.toFloat(),
                    (patchW - 1 - x).toFloat(),
                    y.toFloat(),
                    (patchH - 1 - y).toFloat()
                )
                val linear = (edgeDistance / feather).coerceIn(0f, 1f)
                val alpha = linear * linear * (3f - 2f * linear)
                val index = y * patchW + x
                originalPixels[index] = blendRgb(
                    originalPixels[index],
                    generatedPixels[index],
                    alpha
                )
            }
        }

        output.setPixels(
            originalPixels,
            0,
            patchW,
            left,
            top,
            patchW,
            patchH
        )
    }

    /**
     * Квадратный контекст предотвращает геометрическое растяжение картинки
     * при приведении к фиксированному входу LaMa 512x512.
     */
    private fun buildContextRect(mask: RectF, width: Int, height: Int): RectF {
        val minSide = min(width, height).toFloat().coerceAtLeast(2f)
        val desiredSide = max(
            256f,
            max(mask.width() * 3.5f, mask.height() * 6.5f)
        ).coerceAtMost(minSide)

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

        left = left.coerceIn(0f, (width - 2).toFloat().coerceAtLeast(0f))
        top = top.coerceIn(0f, (height - 2).toFloat().coerceAtLeast(0f))
        right = right.coerceIn(left + 2f, width.toFloat())
        bottom = bottom.coerceIn(top + 2f, height.toFloat())

        return RectF(left, top, right, bottom)
    }

    /**
     * OCR/трекер обычно возвращает рамку по видимым буквам. Увеличиваем её,
     * чтобы полностью удалить полупрозрачное сглаживание и тень watermark.
     */
    private fun expandedMask(rect: RectF, width: Int, height: Int): RectF {
        val safeW = width.coerceAtLeast(3)
        val safeH = height.coerceAtLeast(3)
        val padX = max(6f, rect.width() * 0.12f)
        val padY = max(6f, rect.height() * 0.18f)

        val left = (rect.left - padX).coerceIn(0f, safeW.toFloat() - 2f)
        val top = (rect.top - padY).coerceIn(0f, safeH.toFloat() - 2f)
        val right = (rect.right + padX).coerceIn(left + 2f, safeW.toFloat())
        val bottom = (rect.bottom + padY).coerceIn(top + 2f, safeH.toFloat())

        return RectF(left, top, right, bottom)
    }

    /**
     * g-ronimo/lama_512_int8:
     * input = float32 [1,4,512,512].
     * RGB 0..1, внутри маски RGB=0; четвёртый канал — бинарная mask 0/1.
     */
    private fun combinedInputTensor(
        env: OrtEnvironment,
        bitmap: Bitmap,
        mask: RectF
    ): OnnxTensor {
        val width = bitmap.width
        val height = bitmap.height
        require(width == MODEL_INPUT_SIZE && height == MODEL_INPUT_SIZE) {
            "LaMa ожидает 512x512, получено ${width}x${height}"
        }

        val size = width * height
        val pixels = IntArray(size)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val maskLeft = mask.left.toInt().coerceIn(0, width)
        val maskTop = mask.top.toInt().coerceIn(0, height)
        val maskRight = mask.right.roundToInt().coerceIn(maskLeft, width)
        val maskBottom = mask.bottom.roundToInt().coerceIn(maskTop, height)

        val maskFlags = ByteArray(size)
        for (y in maskTop until maskBottom) {
            val row = y * width
            for (x in maskLeft until maskRight) {
                maskFlags[row + x] = 1
            }
        }

        val buffer = FloatBuffer.allocate(4 * size)

        for (channel in 0 until 3) {
            val offset = channel * size
            for (i in 0 until size) {
                val value = if (maskFlags[i].toInt() != 0) {
                    0f
                } else {
                    val color = when (channel) {
                        0 -> Color.red(pixels[i])
                        1 -> Color.green(pixels[i])
                        else -> Color.blue(pixels[i])
                    }
                    color / 255f
                }
                buffer.put(offset + i, value)
            }
        }

        for (i in 0 until size) {
            buffer.put(3 * size + i, if (maskFlags[i].toInt() != 0) 1f else 0f)
        }

        buffer.rewind()
        return OnnxTensor.createTensor(
            env,
            buffer,
            longArrayOf(1, 4, height.toLong(), width.toLong())
        )
    }

    private fun getSession(context: Context): OrtSession {
        cachedSession?.let { return it }

        synchronized(sessionLock) {
            cachedSession?.let { return it }

            val assetNames = context.assets.list("") ?: emptyArray()
            if (MODEL_ASSET !in assetNames) {
                throw IllegalStateException(
                    "В APK отсутствует $MODEL_ASSET"
                )
            }

            val modelBytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
            val session = ortEnv.createSession(modelBytes)

            // Защита от незаметной подмены модели/неверного I/O контракта.
            val inputInfo = session.inputInfo
            if (!inputInfo.containsKey(INPUT_NAME)) {
                session.close()
                throw IllegalStateException(
                    "У LaMa нет входа '$INPUT_NAME': ${inputInfo.keys.joinToString()}"
                )
            }

            cachedSession = session
            return session
        }
    }

    /** Выход модели — RGB float32 0..1, CHW. */
    private fun tensorToBitmap(tensor: OnnxTensor, width: Int, height: Int): Bitmap {
        val buffer = tensor.floatBuffer
        buffer.rewind()

        val expected = 3 * width * height
        if (buffer.remaining() < expected) {
            throw IllegalStateException(
                "Неверный размер выхода LaMa: ${buffer.remaining()}, ожидалось $expected"
            )
        }

        val size = width * height
        val pixels = IntArray(size)

        for (i in 0 until size) {
            val r = (buffer.get(i) * 255f).roundToInt().coerceIn(0, 255)
            val g = (buffer.get(size + i) * 255f).roundToInt().coerceIn(0, 255)
            val b = (buffer.get(2 * size + i) * 255f).roundToInt().coerceIn(0, 255)
            pixels[i] = Color.rgb(r, g, b)
        }

        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            it.setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }

    private fun blendRgb(original: Int, generated: Int, alpha: Float): Int {
        if (alpha <= 0f) return original
        if (alpha >= 1f) return Color.rgb(
            Color.red(generated),
            Color.green(generated),
            Color.blue(generated)
        )

        val inverse = 1f - alpha
        return Color.rgb(
            (Color.red(original) * inverse + Color.red(generated) * alpha)
                .roundToInt()
                .coerceIn(0, 255),
            (Color.green(original) * inverse + Color.green(generated) * alpha)
                .roundToInt()
                .coerceIn(0, 255),
            (Color.blue(original) * inverse + Color.blue(generated) * alpha)
                .roundToInt()
                .coerceIn(0, 255)
        )
    }
}
