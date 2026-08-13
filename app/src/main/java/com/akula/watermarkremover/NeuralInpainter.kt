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
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.nio.FloatBuffer

/**
 * "Качественный" режим удаления вотермарка: вместо простого delogo
 * прогоняет каждый кадр через нейросеть image-inpainting (LaMa),
 * которая реалистично достраивает фон под маской.
 *
 * ВАЖНО перед первой сборкой:
 * 1. Нужен файл модели в формате ONNX, положить в app/src/main/assets/lama.onnx
 *    (сама модель НЕ включена в этот проект — веса не входят в исходники,
 *    её нужно взять отдельно, см. README, раздел "Модель для inpainting").
 * 2. Названия входов/выходов тензоров (INPUT_IMAGE_NAME и т.д. ниже)
 *    соответствуют типичному ONNX-экспорту LaMa, но могут отличаться
 *    в зависимости от конкретного файла — проверь через Netron
 *    (https://netron.app, открыть .onnx и посмотреть имена входов/выходов)
 *    и поправь константы, если не совпадёт.
 *
 * Это CPU-инференс (ONNX Runtime Mobile), на Snapdragon 6 Gen 1 обработка
 * будет заметно медленнее, чем delogo — секунды на кадр, не кадры в секунду.
 * Для 10-секундного ролика на 30fps это 300 кадров — рассчитывай на
 * несколько минут обработки, покажи прогресс, чтобы не выглядело зависшим.
 */
object NeuralInpainter {

    interface Callback {
        fun onProgress(percent: Int, stage: String)
        fun onSuccess(outputPath: String)
        fun onError(message: String)
    }

    private const val MODEL_ASSET = "lama.onnx"
    private const val MODEL_INPUT_SIZE = 512 // большинство экспортов LaMa ждут 512x512

    // Проверь и поправь под свой конкретный .onnx файл (см. Netron).
    private const val INPUT_IMAGE_NAME = "image"
    private const val INPUT_MASK_NAME = "mask"
    private const val OUTPUT_NAME = "output"

    fun process(
        context: Context,
        inputPath: String,
        outputPath: String,
        keyframes: List<MaskKeyframe>,
        videoDurationMs: Long,
        callback: Callback
    ) {
        // Вся обработка тяжёлая — уводим в отдельный поток,
        // callback дальше можно постить обратно на UI-поток из вызывающего кода.
        Thread {
            try {
                runPipeline(context, inputPath, outputPath, keyframes, videoDurationMs, callback)
            } catch (e: Exception) {
                callback.onError("Ошибка нейросетевой обработки: ${e.message}")
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
        val workDir = File(context.cacheDir, "inpaint_${System.currentTimeMillis()}")
        val framesDir = File(workDir, "frames").apply { mkdirs() }
        val outFramesDir = File(workDir, "frames_out").apply { mkdirs() }

        // 1. Разбираем видео на кадры (PNG, оригинальный fps через -vsync 0).
        callback.onProgress(0, "Извлечение кадров")
        val extractCmd = arrayOf(
            "-y", "-i", inputPath, "-vsync", "0",
            "${framesDir.absolutePath}/frame_%06d.png"
        )
        val extractSession = FFmpegKit.executeWithArguments(extractCmd)
        if (!ReturnCode.isSuccess(extractSession.returnCode)) {
            callback.onError("Не удалось извлечь кадры: ${extractSession.returnCode}")
            return
        }

        val frameFiles = framesDir.listFiles { f -> f.name.endsWith(".png") }
            ?.sortedBy { it.name } ?: emptyList()
        if (frameFiles.isEmpty()) {
            callback.onError("Кадры не найдены после извлечения")
            return
        }

        // 2. Готовим ONNX Runtime сессию.
        val ortEnv = OrtEnvironment.getEnvironment()
        val modelBytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
        val session = ortEnv.createSession(modelBytes)

        val sortedKeyframes = keyframes.sortedBy { it.timeMs }
        val totalFrames = frameFiles.size

        // 3. Каждый кадр: собираем маску (интерполированную по времени),
        // прогоняем через модель, сохраняем результат.
        frameFiles.forEachIndexed { index, frameFile ->
            val timeMs = if (totalFrames > 1) {
                (index.toLong() * videoDurationMs) / (totalFrames - 1)
            } else 0L

            val maskRect = MaskTracker.interpolate(sortedKeyframes, timeMs)
            val srcBitmap = BitmapFactory.decodeFile(frameFile.absolutePath)

            val resultBitmap = inpaintFrame(ortEnv, session, srcBitmap, maskRect)
            File(outFramesDir, frameFile.name).outputStream().use { out ->
                resultBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            srcBitmap.recycle()
            resultBitmap.recycle()

            val percent = ((index + 1) * 100) / totalFrames
            callback.onProgress(percent, "Нейросеть: кадр ${index + 1}/$totalFrames")
        }
        session.close()

        // 4. Собираем видео обратно из обработанных кадров + звук из оригинала,
        // сразу приводим к 1080p.
        callback.onProgress(99, "Сборка видео")
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

        // Чистим временные кадры вне зависимости от результата.
        workDir.deleteRecursively()

        if (ReturnCode.isSuccess(assembleSession.returnCode)) {
            callback.onSuccess(outputPath)
        } else {
            callback.onError("Не удалось собрать итоговое видео: ${assembleSession.returnCode}")
        }
    }

    /**
     * Прогоняет один кадр через LaMa: ресайз до входа модели,
     * нормализация, инференс, ресайз обратно.
     */
    private fun inpaintFrame(
        env: OrtEnvironment,
        session: OrtSession,
        srcBitmap: Bitmap,
        maskRect: android.graphics.RectF
    ): Bitmap {
        val origW = srcBitmap.width
        val origH = srcBitmap.height

        val resized = Bitmap.createScaledBitmap(srcBitmap, MODEL_INPUT_SIZE, MODEL_INPUT_SIZE, true)

        // Маска модели: 1.0 внутри вотермарка, 0.0 снаружи, в координатах 512x512.
        val maskBitmap = Bitmap.createBitmap(MODEL_INPUT_SIZE, MODEL_INPUT_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(maskBitmap)
        canvas.drawColor(Color.BLACK)
        val paint = Paint().apply { color = Color.WHITE; isAntiAlias = true }
        val scaleX = MODEL_INPUT_SIZE / origW.toFloat()
        val scaleY = MODEL_INPUT_SIZE / origH.toFloat()
        canvas.drawRect(
            maskRect.left * scaleX, maskRect.top * scaleY,
            maskRect.right * scaleX, maskRect.bottom * scaleY,
            paint
        )

        val imageTensor = bitmapToTensor(env, resized)
        val maskTensor = maskToTensor(env, maskBitmap)

        val inputs = mapOf(
            INPUT_IMAGE_NAME to imageTensor,
            INPUT_MASK_NAME to maskTensor
        )

        val outputBitmap: Bitmap
        session.run(inputs).use { result ->
            val outputTensor = result.get(OUTPUT_NAME).get() as OnnxTensor
            outputBitmap = tensorToBitmap(outputTensor, MODEL_INPUT_SIZE, MODEL_INPUT_SIZE)
        }
        imageTensor.close()
        maskTensor.close()
        resized.recycle()
        maskBitmap.recycle()

        // Возвращаем результат к исходному разрешению кадра.
        return Bitmap.createScaledBitmap(outputBitmap, origW, origH, true)
    }

    private fun bitmapToTensor(env: OrtEnvironment, bitmap: Bitmap): OnnxTensor {
        val size = bitmap.width * bitmap.height
        val floatBuffer = FloatBuffer.allocate(3 * size)
        val pixels = IntArray(size)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        // CHW, нормализация 0..1 — стандартный формат для большинства экспортов LaMa.
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
        val shape = longArrayOf(1, 3, bitmap.height.toLong(), bitmap.width.toLong())
        return OnnxTensor.createTensor(env, floatBuffer, shape)
    }

    private fun maskToTensor(env: OrtEnvironment, maskBitmap: Bitmap): OnnxTensor {
        val size = maskBitmap.width * maskBitmap.height
        val floatBuffer = FloatBuffer.allocate(size)
        val pixels = IntArray(size)
        maskBitmap.getPixels(pixels, 0, maskBitmap.width, 0, 0, maskBitmap.width, maskBitmap.height)
        for (i in 0 until size) {
            floatBuffer.put(i, if (Color.red(pixels[i]) > 127) 1f else 0f)
        }
        floatBuffer.rewind()
        val shape = longArrayOf(1, 1, maskBitmap.height.toLong(), maskBitmap.width.toLong())
        return OnnxTensor.createTensor(env, floatBuffer, shape)
    }

    private fun tensorToBitmap(tensor: OnnxTensor, w: Int, h: Int): Bitmap {
        val buffer = tensor.floatBuffer
        val size = w * h
        val pixels = IntArray(size)
        for (i in 0 until size) {
            val r = (buffer.get(i) * 255f).toInt().coerceIn(0, 255)
            val g = (buffer.get(size + i) * 255f).toInt().coerceIn(0, 255)
            val b = (buffer.get(2 * size + i) * 255f).toInt().coerceIn(0, 255)
            pixels[i] = Color.rgb(r, g, b)
        }
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        return bitmap
    }
}
