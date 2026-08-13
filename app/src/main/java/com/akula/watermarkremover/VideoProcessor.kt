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
        if (videoWidth < 2 || videoHeight < 2) {
            callback.onError("Некорректный размер видео: ${videoWidth}x${videoHeight}")
            return
        }

        val frameWidth = videoWidth.toFloat()
        val frameHeight = videoHeight.toFloat()

        val safeKeyframes = keyframes.map { keyframe ->
            if (!keyframe.active) {
                keyframe
            } else {
                val left = keyframe.rect.left.coerceIn(0f, frameWidth)
                val top = keyframe.rect.top.coerceIn(0f, frameHeight)
                val right = keyframe.rect.right.coerceIn(0f, frameWidth)
                val bottom = keyframe.rect.bottom.coerceIn(0f, frameHeight)
                val clipped = RectF(left, top, right, bottom)

                if (clipped.width() < 2f || clipped.height() < 2f) {
                    keyframe.copy(
                        rect = RectF(0f, 0f, 0f, 0f),
                        active = false,
                        confidence = 0f
                    )
                } else {
                    keyframe.copy(
                        rect = RectF(
                            clipped.left + 1f,
                            clipped.top + 1f,
                            clipped.right + 1f,
                            clipped.bottom + 1f
                        )
                    )
                }
            }
        }

        val delogoChain = MaskTracker.buildTrackedDelogoFilter(safeKeyframes, durationMs)
        val filter = "pad=iw+2:ih+2:1:1,$delogoChain,crop=iw-2:ih-2:1:1,scale=-2:1080"

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
