package com.akula.watermarkremover

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.FloatBuffer
import java.security.MessageDigest

/**
 * Локальный LaMa inpainting через ONNX Runtime.
 *
 * Модель не кладём внутрь APK: при первом использовании она скачивается
 * один раз в filesDir/models/lama_fp32.onnx, проверяется SHA-256 и дальше
 * используется полностью локально.
 */
object NeuralInpainter {

    interface Callback {
        fun onProgress(percent: Int, stage: String)
        fun onSuccess(outputPath: String)
        fun onError(message: String)
    }

    interface PhotoCallback {
        fun onProgress(percent: Int, stage: String)
        fun onSuccess(bitmap: Bitmap)
        fun onError(message: String)
    }

    private const val MODEL_FILE_NAME = "lama_fp32.onnx"
    private const val MODEL_INPUT_SIZE = 512
    private const val MODEL_URL =
        "https://huggingface.co/Carve/LaMa-ONNX/resolve/main/lama_fp32.onnx"
    private const val MODEL_SHA256 =
        "1faef5301d78db7dda502fe59966957ec4b79dd64e16f03ed96913c7a4eb68d6"

    private const val INPUT_IMAGE_NAME = "image"
    private const val INPUT_MASK_NAME = "mask"

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
                runPipeline(context, inputPath, outputPath, keyframes, videoDurationMs, callback)
            } catch (e: Exception) {
                callback.onError("Ошибка нейросетевой обработки: ${e.message}")
            }
        }.start()
    }

    fun processPhoto(
        context: Context,
        source: Bitmap,
        maskRect: RectF,
        callback: PhotoCallback
    ) {
        Thread {
            try {
                val modelFile = ensureModel(context) { percent, stage ->
                    callback.onProgress(percent, stage)
                }

                callback.onProgress(96, "Запуск LaMa")
                val env = OrtEnvironment.getEnvironment()
                env.createSession(modelFile.absolutePath).use { session ->
                    val result = inpaintFrame(env, session, source, maskRect)
                    callback.onProgress(100, "Готово")
                    callback.onSuccess(result)
                }
            } catch (e: Exception) {
                callback.onError("LaMa: ${e.message}")
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
        val modelFile = ensureModel(context) { percent, stage ->
            callback.onProgress((percent * 0.15f).toInt(), stage)
        }

        val workDir = File(context.cacheDir, "inpaint_${System.currentTimeMillis()}")
        val framesDir = File(workDir, "frames").apply { mkdirs() }
        val outFramesDir = File(workDir, "frames_out").apply { mkdirs() }

        callback.onProgress(15, "Извлечение кадров")
        val extractCmd = arrayOf(
            "-y", "-i", inputPath, "-vsync", "0",
            "${framesDir.absolutePath}/frame_%06d.png"
        )
        val extractSession = FFmpegKit.executeWithArguments(extractCmd)
        if (!ReturnCode.isSuccess(extractSession.returnCode)) {
            callback.onError("Не удалось извлечь кадры: ${extractSession.returnCode}")
            workDir.deleteRecursively()
            return
        }

        val frameFiles = framesDir.listFiles { f -> f.name.endsWith(".png") }
            ?.sortedBy { it.name } ?: emptyList()
        if (frameFiles.isEmpty()) {
            callback.onError("Кадры не найдены после извлечения")
            workDir.deleteRecursively()
            return
        }

        val env = OrtEnvironment.getEnvironment()
        env.createSession(modelFile.absolutePath).use { session ->
            val sortedKeyframes = keyframes.sortedBy { it.timeMs }
            val totalFrames = frameFiles.size

            // Автоматический баланс скорости/качества:
            // <300 кадров  -> каждый кадр
            // 300-799      -> каждый 2-й кадр
            // 800+         -> каждый 3-й кадр
            val frameStride = when {
                totalFrames >= 800 -> 3
                totalFrames >= 300 -> 2
                else -> 1
            }

            val estimatedNeuralFrames =
                (totalFrames + frameStride - 1) / frameStride

            var neuralRuns = 0
            var lastCleanBitmap: Bitmap? = null
            var lastCleanMask: RectF? = null

            frameFiles.forEachIndexed { index, frameFile ->
                val timeMs = if (totalFrames > 1) {
                    (index.toLong() * videoDurationMs) / (totalFrames - 1)
                } else 0L

                val maskRect = MaskTracker.interpolate(sortedKeyframes, timeMs)
                val srcBitmap = BitmapFactory.decodeFile(frameFile.absolutePath)
                    ?: throw IllegalStateException("Не удалось открыть ${frameFile.name}")

                val maskActive =
                    maskRect.width() >= 2f && maskRect.height() >= 2f

                val runNeural = maskActive && (
                    lastCleanBitmap == null ||
                    lastCleanMask == null ||
                    index % frameStride == 0
                )

                val resultBitmap = when {
                    !maskActive -> {
                        lastCleanBitmap?.recycle()
                        lastCleanBitmap = null
                        lastCleanMask = null
                        srcBitmap
                    }

                    runNeural -> {
                        neuralRuns++
                        inpaintFrame(env, session, srcBitmap, maskRect)
                    }

                    else -> {
                        reusePreviousPatch(
                            srcBitmap = srcBitmap,
                            previousClean = lastCleanBitmap!!,
                            previousMaskRaw = lastCleanMask!!,
                            currentMaskRaw = maskRect
                        )
                    }
                }

                if (runNeural) {
                    lastCleanBitmap?.recycle()
                    lastCleanBitmap =
                        resultBitmap.copy(Bitmap.Config.ARGB_8888, false)
                    lastCleanMask = RectF(maskRect)
                }

                File(outFramesDir, frameFile.name).outputStream().use { out ->
                    resultBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }

                if (resultBitmap !== srcBitmap) {
                    resultBitmap.recycle()
                }
                srcBitmap.recycle()

                val neuralPercent =
                    20 + (((index + 1) * 75) / totalFrames)

                callback.onProgress(
                    neuralPercent,
                    "LaMa: нейро $neuralRuns/~$estimatedNeuralFrames • кадр ${index + 1}/$totalFrames"
                )
            }

            lastCleanBitmap?.recycle()
        }

        callback.onProgress(96, "Сборка видео")
        val assembleCmd = arrayOf(
            "-y",
            "-i", "${outFramesDir.absolutePath}/frame_%06d.png",
            "-i", inputPath,
            "-map", "0:v:0", "-map", "1:a:0?",
            "-vf", "scale=-2:1080",
            "-c:v", "libx264", "-preset", "medium", "-crf", "20",
            "-c:a", "copy",
            "-shortest",
            outputPath
        )
        val assembleSession = FFmpegKit.executeWithArguments(assembleCmd)
        workDir.deleteRecursively()

        if (ReturnCode.isSuccess(assembleSession.returnCode)) {
            callback.onProgress(100, "Готово")
            callback.onSuccess(outputPath)
        } else {
            callback.onError("Не удалось собрать итоговое видео: ${assembleSession.returnCode}")
        }
    }

    private fun ensureModel(
        context: Context,
        progress: (Int, String) -> Unit
    ): File {
        val modelDir = File(context.filesDir, "models").apply { mkdirs() }
        val modelFile = File(modelDir, MODEL_FILE_NAME)

        if (modelFile.exists() && sha256(modelFile).equals(MODEL_SHA256, ignoreCase = true)) {
            progress(95, "LaMa уже установлена")
            return modelFile
        }

        if (modelFile.exists()) modelFile.delete()

        val tempFile = File(modelDir, "$MODEL_FILE_NAME.download")
        if (tempFile.exists()) tempFile.delete()

        progress(0, "Скачивание LaMa (~208 МБ)")

        val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", "WatermarkRemover/1.0")
        }

        try {
            connection.connect()
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("сервер модели: HTTP ${connection.responseCode}")
            }

            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    var downloaded = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (total > 0) {
                            val p = ((downloaded * 94L) / total).toInt().coerceIn(0, 94)
                            progress(p, "Скачивание LaMa: ${(downloaded / 1024 / 1024)} МБ")
                        }
                    }
                    output.flush()
                }
            }
        } finally {
            connection.disconnect()
        }

        progress(95, "Проверка модели")
        val hash = sha256(tempFile)
        if (!hash.equals(MODEL_SHA256, ignoreCase = true)) {
            tempFile.delete()
            throw IllegalStateException("контрольная сумма модели не совпала")
        }

        if (!tempFile.renameTo(modelFile)) {
            tempFile.copyTo(modelFile, overwrite = true)
            tempFile.delete()
        }

        return modelFile
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun inpaintFrame(
        env: OrtEnvironment,
        session: OrtSession,
        srcBitmap: Bitmap,
        rawMaskRect: RectF
    ): Bitmap {
        val origW = srcBitmap.width
        val origH = srcBitmap.height
        if (origW <= 1 || origH <= 1) return srcBitmap.copy(Bitmap.Config.ARGB_8888, true)

        val maskRect = expandedMask(rawMaskRect, origW, origH)
        val resized = Bitmap.createScaledBitmap(srcBitmap, MODEL_INPUT_SIZE, MODEL_INPUT_SIZE, true)

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

        val scaleX = MODEL_INPUT_SIZE / origW.toFloat()
        val scaleY = MODEL_INPUT_SIZE / origH.toFloat()
        canvas.drawRect(
            maskRect.left * scaleX,
            maskRect.top * scaleY,
            maskRect.right * scaleX,
            maskRect.bottom * scaleY,
            paint
        )

        val imageTensor = bitmapToTensor(env, resized)
        val maskTensor = maskToTensor(env, maskBitmap)

        val inputs = mapOf(
            INPUT_IMAGE_NAME to imageTensor,
            INPUT_MASK_NAME to maskTensor
        )

        val output512: Bitmap
        session.run(inputs).use { result ->
            val outputTensor = result[0] as OnnxTensor
            output512 = tensorToBitmap(outputTensor, MODEL_INPUT_SIZE, MODEL_INPUT_SIZE)
        }

        imageTensor.close()
        maskTensor.close()
        resized.recycle()
        maskBitmap.recycle()

        val generated = Bitmap.createScaledBitmap(output512, origW, origH, true)
        output512.recycle()

        // Вне маски сохраняем исходный кадр пиксель-в-пиксель.
        // Внутри маски смешиваем результат LaMa, делая мягкий внутренний край.
        val result = srcBitmap.copy(Bitmap.Config.ARGB_8888, true)
        val srcPixels = IntArray(origW * origH)
        val genPixels = IntArray(origW * origH)
        result.getPixels(srcPixels, 0, origW, 0, 0, origW, origH)
        generated.getPixels(genPixels, 0, origW, 0, 0, origW, origH)

        val left = maskRect.left.toInt().coerceIn(0, origW - 1)
        val top = maskRect.top.toInt().coerceIn(0, origH - 1)
        val right = maskRect.right.toInt().coerceIn(left + 1, origW)
        val bottom = maskRect.bottom.toInt().coerceIn(top + 1, origH)
        val feather = maxOf(3f, minOf(maskRect.width(), maskRect.height()) * 0.06f)

        for (y in top until bottom) {
            for (x in left until right) {
                val edgeDistance = minOf(
                    x - maskRect.left,
                    maskRect.right - x,
                    y - maskRect.top,
                    maskRect.bottom - y
                ).coerceAtLeast(0f)
                val alpha = (edgeDistance / feather).coerceIn(0f, 1f)
                val index = y * origW + x
                srcPixels[index] = blend(srcPixels[index], genPixels[index], alpha)
            }
        }

        result.setPixels(srcPixels, 0, origW, 0, 0, origW, origH)
        generated.recycle()
        return result
    }

    /**
     * Для пропущенного кадра сохраняем сам текущий кадр,
     * а из последнего обработанного LaMa-кадра переносим только
     * очищенную область watermark. Поэтому движение видео не замирает.
     */
    private fun reusePreviousPatch(
        srcBitmap: Bitmap,
        previousClean: Bitmap,
        previousMaskRaw: RectF,
        currentMaskRaw: RectF
    ): Bitmap {
        val width = srcBitmap.width
        val height = srcBitmap.height

        if (previousClean.width != width || previousClean.height != height) {
            return srcBitmap.copy(Bitmap.Config.ARGB_8888, true)
        }

        val previousMask =
            expandedMask(previousMaskRaw, width, height)
        val currentMask =
            expandedMask(currentMaskRaw, width, height)

        val result =
            srcBitmap.copy(Bitmap.Config.ARGB_8888, true)

        val currentPixels = IntArray(width * height)
        val previousPixels = IntArray(width * height)

        result.getPixels(
            currentPixels, 0, width,
            0, 0, width, height
        )

        previousClean.getPixels(
            previousPixels, 0, width,
            0, 0, width, height
        )

        val left =
            currentMask.left.toInt().coerceIn(0, width - 1)
        val top =
            currentMask.top.toInt().coerceIn(0, height - 1)
        val right =
            currentMask.right.toInt().coerceIn(left + 1, width)
        val bottom =
            currentMask.bottom.toInt().coerceIn(top + 1, height)

        val currentW = currentMask.width().coerceAtLeast(1f)
        val currentH = currentMask.height().coerceAtLeast(1f)
        val previousW = previousMask.width().coerceAtLeast(1f)
        val previousH = previousMask.height().coerceAtLeast(1f)

        val feather = maxOf(
            3f,
            minOf(currentW, currentH) * 0.06f
        )

        for (y in top until bottom) {
            val v =
                ((y - currentMask.top) / currentH).coerceIn(0f, 1f)

            val sourceY =
                (previousMask.top + v * previousH)
                    .toInt()
                    .coerceIn(0, height - 1)

            for (x in left until right) {
                val u =
                    ((x - currentMask.left) / currentW)
                        .coerceIn(0f, 1f)

                val sourceX =
                    (previousMask.left + u * previousW)
                        .toInt()
                        .coerceIn(0, width - 1)

                val edgeDistance = minOf(
                    x - currentMask.left,
                    currentMask.right - x,
                    y - currentMask.top,
                    currentMask.bottom - y
                ).coerceAtLeast(0f)

                val alpha =
                    (edgeDistance / feather).coerceIn(0f, 1f)

                val dstIndex = y * width + x
                val srcIndex = sourceY * width + sourceX

                currentPixels[dstIndex] = blend(
                    currentPixels[dstIndex],
                    previousPixels[srcIndex],
                    alpha
                )
            }
        }

        result.setPixels(
            currentPixels, 0, width,
            0, 0, width, height
        )

        return result
    }

    private fun expandedMask(rect: RectF, width: Int, height: Int): RectF {
        val padX = maxOf(4f, rect.width() * 0.08f)
        val padY = maxOf(4f, rect.height() * 0.12f)
        return RectF(
            (rect.left - padX).coerceIn(0f, width.toFloat() - 2f),
            (rect.top - padY).coerceIn(0f, height.toFloat() - 2f),
            (rect.right + padX).coerceIn(2f, width.toFloat()),
            (rect.bottom + padY).coerceIn(2f, height.toFloat())
        )
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

    private fun bitmapToTensor(env: OrtEnvironment, bitmap: Bitmap): OnnxTensor {
        val size = bitmap.width * bitmap.height
        val floatBuffer = FloatBuffer.allocate(3 * size)
        val pixels = IntArray(size)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        for (c in 0 until 3) {
            for (i in 0 until size) {
                val p = pixels[i]
                val v = when (c) {
                    0 -> Color.red(p)
                    1 -> Color.green(p)
                    else -> Color.blue(p)
                }
                floatBuffer.put(c * size + i, v / 255f)
            }
        }
        floatBuffer.rewind()
        return OnnxTensor.createTensor(
            env,
            floatBuffer,
            longArrayOf(1, 3, bitmap.height.toLong(), bitmap.width.toLong())
        )
    }

    private fun maskToTensor(env: OrtEnvironment, bitmap: Bitmap): OnnxTensor {
        val size = bitmap.width * bitmap.height
        val floatBuffer = FloatBuffer.allocate(size)
        val pixels = IntArray(size)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        for (i in 0 until size) {
            floatBuffer.put(i, if (Color.red(pixels[i]) > 127) 1f else 0f)
        }
        floatBuffer.rewind()
        return OnnxTensor.createTensor(
            env,
            floatBuffer,
            longArrayOf(1, 1, bitmap.height.toLong(), bitmap.width.toLong())
        )
    }

    private fun tensorToBitmap(tensor: OnnxTensor, w: Int, h: Int): Bitmap {
        val buffer = tensor.floatBuffer
        buffer.rewind()
        val size = w * h
        val pixels = IntArray(size)
        for (i in 0 until size) {
            val r = (buffer.get(i) * 255f).toInt().coerceIn(0, 255)
            val g = (buffer.get(size + i) * 255f).toInt().coerceIn(0, 255)
            val b = (buffer.get(2 * size + i) * 255f).toInt().coerceIn(0, 255)
            pixels[i] = Color.rgb(r, g, b)
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            it.setPixels(pixels, 0, w, 0, 0, w, h)
        }
    }
}
