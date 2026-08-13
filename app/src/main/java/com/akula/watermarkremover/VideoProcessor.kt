package com.akula.watermarkremover

import android.graphics.RectF
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.Statistics

/**
 * Оборачивает FFmpeg-kit: убирает область (delogo, с трекингом или без)
 * и приводит видео к 1080p. Это "быстрый" режим — без нейросети.
 */
object VideoProcessor {

    interface Callback {
        fun onProgress(percent: Int)
        fun onSuccess(outputPath: String)
        fun onError(message: String)
    }

    /**
     * @param keyframes один или несколько ключевых кадров маски.
     *   Один кадр = статичная маска (как в первой версии).
     *   Несколько = маска "едет" по интерполяции между ними.
     */
    fun removeWatermarkAndExport(
        inputPath: String,
        outputPath: String,
        keyframes: List<MaskKeyframe>,
        durationMs: Long,
        videoWidth: Int,
        videoHeight: Int,
        callback: Callback
    ) {
        if (videoWidth < 6 || videoHeight < 6) {
            callback.onError("Некорректный размер видео: ${videoWidth}x${videoHeight}")
            return
        }

        val frameWidth = videoWidth.toFloat()
        val frameHeight = videoHeight.toFloat()
        val maxRight = frameWidth - 1f
        val maxBottom = frameHeight - 1f

        /*
         * delogo внутри использует пограничные пиксели вокруг области.
         * Поэтому маску нельзя ставить ровно на внешний край кадра.
         *
         * Дополнительно слегка расширяем область. Автотрекер часто находит
         * именно буквы полупрозрачного watermark, а delogo должен получать
         * немного чистого фона вокруг надписи, иначе часть текста остаётся.
         */
        val safeKeyframes = keyframes.map { keyframe ->
            if (!keyframe.active || keyframe.rect.width() < 2f || keyframe.rect.height() < 2f) {
                keyframe.copy(
                    rect = RectF(),
                    active = false,
                    confidence = 0f
                )
            } else {
                val extraX = maxOf(3f, keyframe.rect.width() * 0.22f)
                val extraY = maxOf(3f, keyframe.rect.height() * 0.30f)

                val left = (keyframe.rect.left - extraX).coerceAtLeast(1f)
                val top = (keyframe.rect.top - extraY).coerceAtLeast(1f)
                val right = (keyframe.rect.right + extraX).coerceAtMost(maxRight)
                val bottom = (keyframe.rect.bottom + extraY).coerceAtMost(maxBottom)

                if (right - left < 2f || bottom - top < 2f) {
                    keyframe.copy(
                        rect = RectF(),
                        active = false,
                        confidence = 0f
                    )
                } else {
                    keyframe.copy(
                        rect = RectF(left, top, right, bottom)
                    )
                }
            }
        }

        if (safeKeyframes.none { it.active && it.rect.width() >= 2f && it.rect.height() >= 2f }) {
            callback.onError("Не найдено ни одной рабочей области watermark для удаления")
            return
        }

        val delogoChain = MaskTracker.buildTrackedDelogoFilter(safeKeyframes, durationMs)
        if (delogoChain == "null" || delogoChain.isBlank()) {
            callback.onError("FFmpeg не получил ни одной активной маски watermark")
            return
        }

        // Важно: delogo снова применяется прямо к исходному кадру.
        // Предыдущий pad/crop-обход больше не нужен: границы масок уже
        // безопасно ограничены выше, поэтому результат не превращается
        // в фактически неизменённое видео.
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
                    val logTail = fullLog
                        .lines()
                        .takeLast(20)
                        .joinToString("\n")

                    callback.onError(
                        "FFmpeg code: ${session.returnCode}\n\n$logTail"
                    )
                }
            },
            null
        ) { stats: Statistics ->
            if (durationMs > 0) {
                val percent = ((stats.time.toFloat() / durationMs.toFloat()) * 100).toInt()
                callback.onProgress(percent.coerceIn(0, 100))
            }
        }
    }
}
