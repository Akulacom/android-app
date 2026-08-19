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
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.nio.FloatBuffer
import java.util.Locale

/**
 * Быстрый качественный режим.
 *
 * LaMa 512 INT8 уже лежит внутри APK. Мы НЕ извлекаем все кадры видео и
 * НЕ запускаем нейросеть на каждом кадре. Автотрекер заранее знает, когда
 * watermark активен (MaskKeyframe.active), поэтому LaMa запускается только
 * на активных точках трекинга. Из каждого обработанного кадра сохраняется
 * маленькая чистая заплатка, которую FFmpeg накладывает только на временной
 * интервал этой точки.
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
     * Поднимаем ONNX-сессию заранее, пока пользователь выбирает/тречит видео.
     * Повторные обработки в том же процессе используют уже готовую сессию.
     */
    fun warmUp(context: Context) {
        if (cachedSession != null) return
        Thread {
            try {
                getSession(context.applicationContext)
            } catch (_: Throwable) {
                // Если прогрев не удался, основная обработка покажет точную ошибку.
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

        callback.onProgress(3, "Встроенная LaMa INT8 готовится")
        val session = getSession(context)

        val workDir = File(context.cacheDir, "lama_patches_${System.currentTimeMillis()}")
            .apply { mkdirs() }

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(inputPath)

            val metadataW = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
            )?.toIntOrNull()?.coerceAtLeast(1) ?: 1

            val metadataH = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
            )?.toIntOrNull()?.coerceAtLeast(1) ?: 1

            val durationMs = videoDurationMs.coerceAtLeast(1L)
            val ops = ArrayList<PatchOp>()

            activeIndexes.forEachIndexed { activePosition, keyIndex ->
                val key = sorted[keyIndex]

                val frame = retriever.getFrameAtTime(
                    key.timeMs.coerceIn(0L, durationMs) * 1000L,
                    MediaMetadataRetriever.OPTION_CLOSEST
                ) ?: return@forEachIndexed

                try {
                    val targetRect = expandedMask(
                        key.rect,
                        metadataW,
                        metadataH
                    )

                    val frameMaskRaw = scaleRect(
                        key.rect,
                        metadataW,
                        metadataH,
                        frame.width,
                        frame.height
                    )

                    val clean = inpaintFrame(
                        env = ortEnv,
                        session = session,
                        srcBitmap = frame,
                        rawMaskRect = frameMaskRaw
                    )

                    try {
                        val frameCropRect = expandedMask(
                            frameMaskRaw,
                            frame.width,
                            frame.height
                        )

                        val cropLeft = frameCropRect.left.toInt()
                            .coerceIn(0, frame.width - 1)
                        val cropTop = frameCropRect.top.toInt()
                            .coerceIn(0, frame.height - 1)
                        val cropRight = frameCropRect.right.toInt()
                            .coerceIn(cropLeft + 1, frame.width)
                        val cropBottom = frameCropRect.bottom.toInt()
                            .coerceIn(cropTop + 1, frame.height)

                        val crop = Bitmap.createBitmap(
                            clean,
                            cropLeft,
                            cropTop,
                            cropRight - cropLeft,
                            cropBottom - cropTop
                        )

                        val targetW = targetRect.width().toInt().coerceAtLeast(2)
                        val targetH = targetRect.height().toInt().coerceAtLeast(2)
                        val patch = if (crop.width != targetW || crop.height != targetH) {
                            Bitmap.createScaledBitmap(crop, targetW, targetH, true)
                        } else {
                            crop
                        }

                        val patchFile = File(
                            workDir,
                            "patch_${activePosition.toString().padStart(3, '0')}.png"
                        )

                        patchFile.outputStream().use { out ->
                            if (!patch.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                                throw IllegalStateException("Не удалось сохранить AI patch")
                            }
                        }

                        if (patch !== crop) patch.recycle()
                        crop.recycle()

                        val startMs = if (keyIndex == 0) {
                            0L
                        } else {
                            midpoint(sorted[keyIndex - 1].timeMs, key.timeMs)
                        }.coerceIn(0L, durationMs)

                        val endMs = if (keyIndex == sorted.lastIndex) {
                            durationMs
                        } else {
                            midpoint(key.timeMs, sorted[keyIndex + 1].timeMs)
                        }.coerceIn(startMs + 1L, durationMs)

                        ops.add(
                            PatchOp(
                                startMs = startMs,
                                endMs = endMs,
                                rect = targetRect,
                                file = patchFile
                            )
                        )
                    } finally {
                        clean.recycle()
                    }
                } finally {
                    frame.recycle()
                }

                val done = activePosition + 1
                val percent = 8 + (done * 67 / activeIndexes.size)
                callback.onProgress(
                    percent.coerceIn(8, 75),
                    "LaMa: $done/${activeIndexes.size} активных точек"
                )
            }

            if (ops.isEmpty()) {
                callback.onError("Не удалось получить кадры для активного watermark")
                return
            }

            callback.onProgress(80, "Быстрая сборка видео")
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
            try { retriever.release() } catch (_: Throwable) {}
            workDir.deleteRecursively()
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
                "-preset", "veryfast",
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

    private fun inpaintFrame(
        env: OrtEnvironment,
        session: OrtSession,
        srcBitmap: Bitmap,
        rawMaskRect: RectF
    ): Bitmap {
        val origW = srcBitmap.width
        val origH = srcBitmap.height
        if (origW <= 1 || origH <= 1) {
            return srcBitmap.copy(Bitmap.Config.ARGB_8888, true)
        }

        val maskRect = expandedMask(rawMaskRect, origW, origH)
        val resized = Bitmap.createScaledBitmap(
            srcBitmap,
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

        val scaleX = MODEL_INPUT_SIZE / origW.toFloat()
        val scaleY = MODEL_INPUT_SIZE / origH.toFloat()
        canvas.drawRect(
            maskRect.left * scaleX,
            maskRect.top * scaleY,
            maskRect.right * scaleX,
            maskRect.bottom * scaleY,
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

        val generated = Bitmap.createScaledBitmap(output512, origW, origH, true)
        output512.recycle()

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
                val pixelIndex = y * origW + x
                srcPixels[pixelIndex] = blend(
                    srcPixels[pixelIndex],
                    genPixels[pixelIndex],
                    alpha
                )
            }
        }

        result.setPixels(srcPixels, 0, origW, 0, 0, origW, origH)
        generated.recycle()
        return result
    }

    /**
     * g-ronimo/lama_512_int8 принимает один tensor [1,4,512,512]:
     * RGB с занулённой маской + бинарный mask-канал.
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
