package com.akula.watermarkremover

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
        callback: Callback
    ) {
        val delogoChain = MaskTracker.buildTrackedDelogoFilter(keyframes, durationMs)
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
                    val logTail = if (fullLog.length > 3500) {
                        fullLog.takeLast(3500)
                    } else {
                        fullLog
                    }

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
