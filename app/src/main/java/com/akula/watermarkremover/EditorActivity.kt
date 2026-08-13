package com.akula.watermarkremover

import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.akula.watermarkremover.databinding.ActivityEditorBinding
import java.io.File

class EditorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_VIDEO_URI = "extra_video_uri"
    }

    private lateinit var binding: ActivityEditorBinding
    private lateinit var videoUri: Uri

    private var videoWidth = 0
    private var videoHeight = 0
    private var videoDurationMs = 0L

    private val keyframes = mutableListOf<MaskKeyframe>()
    private val uiHandler = Handler(Looper.getMainLooper())
    private var trackingSeekBar = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val uriString = intent.getStringExtra(EXTRA_VIDEO_URI)
            ?: run { finish(); return }
        videoUri = Uri.parse(uriString)

        readVideoMeta()

        binding.videoView.setVideoURI(videoUri)
        binding.videoView.setOnPreparedListener {
            it.isLooping = true
            binding.seekBar.max = videoDurationMs.toInt().coerceAtLeast(1)
        }
        binding.videoView.start()

        startSeekBarSync()

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) binding.videoView.seekTo(progress)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { trackingSeekBar = true }
            override fun onStopTrackingTouch(sb: SeekBar?) { trackingSeekBar = false }
        })

        binding.btnAddKeyframe.setOnClickListener { onAddKeyframe() }
        binding.btnClearKeyframes.setOnClickListener {
            keyframes.clear()
            updateKeyframeLabel()
            Toast.makeText(this, "Точки трекинга сброшены", Toast.LENGTH_SHORT).show()
        }

        binding.btnApply.setOnClickListener { try { binding.videoView.pause(); onApplyClicked() } catch (t: Throwable) { binding.btnApply.isEnabled = true; binding.progressBar.visibility = android.view.View.INVISIBLE; val msg = "Ошибка запуска: ${t.javaClass.simpleName}: ${t.message}"; binding.tvStatus.text = msg; Toast.makeText(this, msg, Toast.LENGTH_LONG).show() } }
    }

    private fun startSeekBarSync() {
        uiHandler.post(object : Runnable {
            override fun run() {
                if (!trackingSeekBar && binding.videoView.isPlaying) {
                    binding.seekBar.progress = binding.videoView.currentPosition
                }
                uiHandler.postDelayed(this, 250)
            }
        })
    }

    private fun readVideoMeta() {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(this, videoUri)
        videoWidth = retriever.extractMetadata(
            MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
        )?.toIntOrNull() ?: 0
        videoHeight = retriever.extractMetadata(
            MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
        )?.toIntOrNull() ?: 0
        videoDurationMs = retriever.extractMetadata(
            MediaMetadataRetriever.METADATA_KEY_DURATION
        )?.toLongOrNull() ?: 0L
        retriever.release()
    }

    /** Переводит текущую рамку маски (в пикселях View) в пиксели видео. */
    private fun currentMaskInVideoCoords(): RectF? {
        if (!binding.maskOverlay.hasMask() ||
            videoWidth <= 0 ||
            videoHeight <= 0
        ) return null

        val overlay = binding.maskOverlay
        val video = binding.videoView

        val viewW = video.width.toFloat()
        val viewH = video.height.toFloat()

        if (viewW <= 0f || viewH <= 0f) return null

        // Реальное положение VideoView относительно MaskOverlay.
        // Работает даже если они находятся в разных контейнерах.
        val videoLocation = IntArray(2)
        val overlayLocation = IntArray(2)

        video.getLocationOnScreen(videoLocation)
        overlay.getLocationOnScreen(overlayLocation)

        val videoViewLeft =
            (videoLocation[0] - overlayLocation[0]).toFloat()
        val videoViewTop =
            (videoLocation[1] - overlayLocation[1]).toFloat()

        // Находим фактический прямоугольник изображения внутри VideoView
        // с учётом сохранения пропорций видео.
        val sourceAspect =
            videoWidth.toFloat() / videoHeight.toFloat()

        val viewAspect =
            viewW / viewH

        val contentW: Float
        val contentH: Float
        val contentLeft: Float
        val contentTop: Float

        if (viewAspect > sourceAspect) {
            contentH = viewH
            contentW = viewH * sourceAspect

            contentLeft =
                videoViewLeft + (viewW - contentW) / 2f
            contentTop =
                videoViewTop
        } else {
            contentW = viewW
            contentH = viewW / sourceAspect

            contentLeft =
                videoViewLeft
            contentTop =
                videoViewTop + (viewH - contentH) / 2f
        }

        val sourceRect = overlay.maskRect

        val contentRight = contentLeft + contentW
        val contentBottom = contentTop + contentH

        val left =
            sourceRect.left.coerceIn(
                contentLeft,
                contentRight - 2f
            )

        val top =
            sourceRect.top.coerceIn(
                contentTop,
                contentBottom - 2f
            )

        val right =
            sourceRect.right.coerceIn(
                left + 2f,
                contentRight
            )

        val bottom =
            sourceRect.bottom.coerceIn(
                top + 2f,
                contentBottom
            )

        val scaleX =
            videoWidth.toFloat() / contentW

        val scaleY =
            videoHeight.toFloat() / contentH

        return RectF(
            ((left - contentLeft) * scaleX)
                .coerceIn(0f, videoWidth.toFloat() - 2f),

            ((top - contentTop) * scaleY)
                .coerceIn(0f, videoHeight.toFloat() - 2f),

            ((right - contentLeft) * scaleX)
                .coerceIn(2f, videoWidth.toFloat()),

            ((bottom - contentTop) * scaleY)
                .coerceIn(2f, videoHeight.toFloat())
        )
    }

    private fun onAddKeyframe() {
        val rect = currentMaskInVideoCoords()
        if (rect == null) {
            Toast.makeText(this, "Сначала выдели область пальцем", Toast.LENGTH_SHORT).show()
            return
        }
        val timeMs = binding.videoView.currentPosition.toLong()
        // Если точка на этом же времени уже есть — заменяем, а не дублируем.
        keyframes.removeAll { kotlin.math.abs(it.timeMs - timeMs) < 50 }
        keyframes.add(MaskKeyframe(timeMs, rect))
        updateKeyframeLabel()
        Toast.makeText(this, "Точка добавлена на ${timeMs / 1000}с", Toast.LENGTH_SHORT).show()
    }

    private fun updateKeyframeLabel() {
        binding.tvKeyframes.text = "Точек трекинга: ${keyframes.size}" +
            if (keyframes.size == 1) " (маска статична)" else if (keyframes.size > 1) " (маска едет по интерполяции)" else ""
    }

    private fun onApplyClicked() {
        // Если пользователь не нажал "Добавить точку" ни разу, но нарисовал
        // рамку — считаем это одной статичной точкой на текущем времени.
        if (keyframes.isEmpty()) {
            val rect = currentMaskInVideoCoords()
            if (rect == null) {
                Toast.makeText(this, "Сначала выдели область с текстом пальцем", Toast.LENGTH_SHORT).show()
                return
            }
            keyframes.add(MaskKeyframe(binding.videoView.currentPosition.toLong(), rect))
        }

        val inputPath = resolveRealPath(videoUri)
        if (inputPath == null) {
            Toast.makeText(this, "Не удалось получить путь к файлу", Toast.LENGTH_SHORT).show()
            return
        }

        val outDir = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "watermark_removed")
        outDir.mkdirs()
        val outputPath = File(outDir, "clean_${System.currentTimeMillis()}.mp4").absolutePath

        binding.btnApply.isEnabled = false
        binding.progressBar.visibility = android.view.View.VISIBLE
        binding.tvStatus.text = "Обработка..."

        if (binding.radioQuality.isChecked) {
            NeuralInpainter.process(
                context = this,
                inputPath = inputPath,
                outputPath = outputPath,
                keyframes = keyframes,
                videoDurationMs = videoDurationMs,
                callback = object : NeuralInpainter.Callback {
                    override fun onProgress(percent: Int, stage: String) {
                        runOnUiThread {
                            binding.progressBar.progress = percent
                            binding.tvStatus.text = "$stage — $percent%"
                        }
                    }
                    override fun onSuccess(outputPath: String) = onProcessingSuccess(outputPath)
                    override fun onError(message: String) = onProcessingError(message)
                }
            )
        } else {
            VideoProcessor.removeWatermarkAndExport(
                inputPath = inputPath,
                outputPath = outputPath,
                keyframes = keyframes,
                durationMs = videoDurationMs,
                callback = object : VideoProcessor.Callback {
                    override fun onProgress(percent: Int) {
                        runOnUiThread {
                            binding.progressBar.progress = percent
                            binding.tvStatus.text = "Обработка... $percent%"
                        }
                    }
                    override fun onSuccess(outputPath: String) = onProcessingSuccess(outputPath)
                    override fun onError(message: String) = onProcessingError(message)
                }
            )
        }
    }

    private fun onProcessingSuccess(outputPath: String) {
        runOnUiThread {
            binding.btnApply.isEnabled = true
            binding.tvStatus.text = "Готово: $outputPath"
            Toast.makeText(this, "Сохранено в 1080p:\n$outputPath", Toast.LENGTH_LONG).show()
        }
    }

    private fun onProcessingError(message: String) {
        runOnUiThread {
            binding.btnApply.isEnabled = true
            binding.tvStatus.text = "Ошибка: $message"
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Для FFmpeg нужен реальный путь к файлу, а не content:// Uri.
     * Простой вариант: копируем во временный файл в кэше приложения.
     */
    private fun resolveRealPath(uri: Uri): String? {
        return try {
            val input = contentResolver.openInputStream(uri) ?: return null
            val tempFile = File(cacheDir, "input_${System.currentTimeMillis()}.mp4")
            tempFile.outputStream().use { output -> input.copyTo(output) }
            input.close()
            tempFile.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        uiHandler.removeCallbacksAndMessages(null)
    }
}
