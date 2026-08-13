package com.akula.watermarkremover
import android.graphics.Bitmap
import android.graphics.Color
import android.provider.MediaStore
import android.os.Build
import android.content.ContentValues

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

    private data class TrackerTemplate(
        val width: Int,
        val height: Int,
        val xs: IntArray,
        val ys: IntArray,
        val gx: IntArray,
        val gy: IntArray
    )


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

        binding.btnAutoTrack.setOnClickListener {
            autoTrackWatermark()
        }

        val prefs = getSharedPreferences("watermark_settings", MODE_PRIVATE)

        binding.cbDeleteInternal.isChecked =
            prefs.getBoolean("delete_internal", false)

        binding.cbDeleteInternal.setOnCheckedChangeListener { _, checked ->
            prefs.edit()
                .putBoolean("delete_internal", checked)
                .apply()
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


    private fun grayValue(pixel: Int): Int {
        return (
            Color.red(pixel) * 30 +
            Color.green(pixel) * 59 +
            Color.blue(pixel) * 11
        ) / 100
    }

    private fun bitmapToGray(bitmap: Bitmap): IntArray {
        val width = bitmap.width
        val height = bitmap.height

        val pixels = IntArray(width * height)

        bitmap.getPixels(
            pixels,
            0,
            width,
            0,
            0,
            width,
            height
        )

        for (i in pixels.indices) {
            pixels[i] = grayValue(pixels[i])
        }

        return pixels
    }

    private fun buildTrackerTemplate(bitmap: Bitmap): TrackerTemplate {
        val width = bitmap.width
        val height = bitmap.height

        val gray = bitmapToGray(bitmap)

        val xs = ArrayList<Int>()
        val ys = ArrayList<Int>()
        val gxList = ArrayList<Int>()
        val gyList = ArrayList<Int>()

        val sampleStep =
            maxOf(1, minOf(width, height) / 12)

        for (y in 1 until height - 1 step sampleStep) {
            for (x in 1 until width - 1 step sampleStep) {
                val index = y * width + x

                val gx =
                    gray[index + 1] -
                    gray[index - 1]

                val gy =
                    gray[index + width] -
                    gray[index - width]

                val strength =
                    kotlin.math.abs(gx) +
                    kotlin.math.abs(gy)

                // Берём в первую очередь края букв/логотипа.
                if (strength >= 18) {
                    xs.add(x)
                    ys.add(y)
                    gxList.add(gx)
                    gyList.add(gy)
                }
            }
        }

        // Если логотип слишком гладкий — используем обычную сетку.
        if (xs.size < 15) {
            xs.clear()
            ys.clear()
            gxList.clear()
            gyList.clear()

            val fallbackStep =
                maxOf(1, minOf(width, height) / 10)

            for (y in 1 until height - 1 step fallbackStep) {
                for (x in 1 until width - 1 step fallbackStep) {
                    val index = y * width + x

                    xs.add(x)
                    ys.add(y)

                    gxList.add(
                        gray[index + 1] -
                        gray[index - 1]
                    )

                    gyList.add(
                        gray[index + width] -
                        gray[index - width]
                    )
                }
            }
        }

        return TrackerTemplate(
            width = width,
            height = height,
            xs = xs.toIntArray(),
            ys = ys.toIntArray(),
            gx = gxList.toIntArray(),
            gy = gyList.toIntArray()
        )
    }

    private fun findWatermarkOnFrame(
        bitmap: Bitmap,
        template: TrackerTemplate
    ): Pair<Rect, Float> {

        val width = bitmap.width
        val height = bitmap.height

        val gray = bitmapToGray(bitmap)

        val maxX = width - template.width
        val maxY = height - template.height

        if (maxX <= 0 || maxY <= 0) {
            return Pair(
                Rect(
                    0,
                    0,
                    template.width.coerceAtMost(width),
                    template.height.coerceAtMost(height)
                ),
                Float.MAX_VALUE
            )
        }

        var bestX = 0
        var bestY = 0
        var bestScore = Float.MAX_VALUE

        fun scoreAt(originX: Int, originY: Int): Float {
            var total = 0L

            for (i in template.xs.indices) {
                val x = originX + template.xs[i]
                val y = originY + template.ys[i]

                val index = y * width + x

                val gx =
                    gray[index + 1] -
                    gray[index - 1]

                val gy =
                    gray[index + width] -
                    gray[index - width]

                total +=
                    kotlin.math.abs(gx - template.gx[i]) +
                    kotlin.math.abs(gy - template.gy[i])
            }

            return if (template.xs.isEmpty()) {
                Float.MAX_VALUE
            } else {
                total.toFloat() /
                    (template.xs.size * 2f)
            }
        }

        // Сначала быстрый поиск по всему кадру.
        val searchStep = 3

        var y = 0

        while (y <= maxY) {
            var x = 0

            while (x <= maxX) {
                val score = scoreAt(x, y)

                if (score < bestScore) {
                    bestScore = score
                    bestX = x
                    bestY = y
                }

                x += searchStep
            }

            y += searchStep
        }

        // Затем уточняем позицию с точностью до пикселя.
        val refineLeft =
            (bestX - 4).coerceAtLeast(0)

        val refineRight =
            (bestX + 4).coerceAtMost(maxX)

        val refineTop =
            (bestY - 4).coerceAtLeast(0)

        val refineBottom =
            (bestY + 4).coerceAtMost(maxY)

        for (ry in refineTop..refineBottom) {
            for (rx in refineLeft..refineRight) {
                val score = scoreAt(rx, ry)

                if (score < bestScore) {
                    bestScore = score
                    bestX = rx
                    bestY = ry
                }
            }
        }

        return Pair(
            Rect(
                bestX,
                bestY,
                bestX + template.width,
                bestY + template.height
            ),
            bestScore
        )
    }

    private fun autoTrackWatermark() {
        val sourceRect = currentMaskInVideoCoords()

        if (sourceRect == null) {
            Toast.makeText(
                this,
                "Сначала обведи watermark красной рамкой",
                Toast.LENGTH_LONG
            ).show()

            return
        }

        if (videoWidth <= 0 ||
            videoHeight <= 0 ||
            videoDurationMs <= 0
        ) {
            Toast.makeText(
                this,
                "Не удалось получить параметры видео",
                Toast.LENGTH_LONG
            ).show()

            return
        }

        binding.videoView.pause()
        binding.btnAutoTrack.isEnabled = false

        val referenceTime =
            binding.videoView.currentPosition.toLong()

        binding.tvStatus.text =
            "Подготовка автотрекинга..."

        Thread {
            val retriever = MediaMetadataRetriever()

            try {
                retriever.setDataSource(this, videoUri)

                val referenceFrame =
                    retriever.getFrameAtTime(
                        referenceTime * 1000L,
                        MediaMetadataRetriever.OPTION_CLOSEST
                    ) ?: throw IllegalStateException(
                        "Не удалось получить исходный кадр"
                    )

                val trackingWidth = 360

                val trackingHeight =
                    maxOf(
                        1,
                        (
                            referenceFrame.height *
                            trackingWidth.toFloat() /
                            referenceFrame.width.toFloat()
                        ).toInt()
                    )

                val referenceScaled =
                    Bitmap.createScaledBitmap(
                        referenceFrame,
                        trackingWidth,
                        trackingHeight,
                        true
                    )

                val left =
                    (
                        sourceRect.left /
                        videoWidth.toFloat() *
                        referenceScaled.width
                    ).toInt().coerceIn(
                        0,
                        referenceScaled.width - 2
                    )

                val top =
                    (
                        sourceRect.top /
                        videoHeight.toFloat() *
                        referenceScaled.height
                    ).toInt().coerceIn(
                        0,
                        referenceScaled.height - 2
                    )

                val right =
                    (
                        sourceRect.right /
                        videoWidth.toFloat() *
                        referenceScaled.width
                    ).toInt().coerceIn(
                        left + 2,
                        referenceScaled.width
                    )

                val bottom =
                    (
                        sourceRect.bottom /
                        videoHeight.toFloat() *
                        referenceScaled.height
                    ).toInt().coerceIn(
                        top + 2,
                        referenceScaled.height
                    )

                val templateBitmap =
                    Bitmap.createBitmap(
                        referenceScaled,
                        left,
                        top,
                        right - left,
                        bottom - top
                    )

                val template =
                    buildTrackerTemplate(templateBitmap)

                if (template.xs.isEmpty()) {
                    throw IllegalStateException(
                        "Не удалось создать шаблон watermark"
                    )
                }

                val tracked =
                    mutableListOf<MaskKeyframe>()

                val stepMs =
                    when {
                        videoDurationMs <= 15_000L -> 400L
                        videoDurationMs <= 30_000L -> 600L
                        else -> 1000L
                    }

                var timeMs = 0L

                while (timeMs <= videoDurationMs) {
                    val frame =
                        retriever.getFrameAtTime(
                            timeMs * 1000L,
                            MediaMetadataRetriever.OPTION_CLOSEST
                        )

                    if (frame != null) {
                        val frameHeight =
                            maxOf(
                                1,
                                (
                                    frame.height *
                                    trackingWidth.toFloat() /
                                    frame.width.toFloat()
                                ).toInt()
                            )

                        val scaled =
                            Bitmap.createScaledBitmap(
                                frame,
                                trackingWidth,
                                frameHeight,
                                true
                            )

                        val result =
                            findWatermarkOnFrame(
                                scaled,
                                template
                            )

                        val found = result.first

                        val rect =
                            RectF(
                                found.left.toFloat() /
                                    scaled.width.toFloat() *
                                    videoWidth.toFloat(),

                                found.top.toFloat() /
                                    scaled.height.toFloat() *
                                    videoHeight.toFloat(),

                                found.right.toFloat() /
                                    scaled.width.toFloat() *
                                    videoWidth.toFloat(),

                                found.bottom.toFloat() /
                                    scaled.height.toFloat() *
                                    videoHeight.toFloat()
                            )

                        tracked.add(
                            MaskKeyframe(
                                timeMs,
                                rect
                            )
                        )
                    }

                    val percent =
                        (
                            timeMs.toFloat() /
                            videoDurationMs.toFloat() *
                            100f
                        ).toInt().coerceIn(0, 100)

                    runOnUiThread {
                        binding.tvStatus.text =
                            "Автотрекинг: $percent%"
                    }

                    timeMs += stepMs
                }

                if (tracked.isEmpty()) {
                    throw IllegalStateException(
                        "Watermark не найден"
                    )
                }

                // Последняя точка до самого конца ролика.
                if (tracked.last().timeMs < videoDurationMs) {
                    tracked.add(
                        MaskKeyframe(
                            videoDurationMs,
                            RectF(tracked.last().rect)
                        )
                    )
                }

                runOnUiThread {
                    keyframes.clear()
                    keyframes.addAll(tracked)

                    updateKeyframeLabel()

                    binding.tvStatus.text =
                        "Автотрекинг готов: ${tracked.size} точек"

                    Toast.makeText(
                        this,
                        "Плавающий watermark отслежен автоматически",
                        Toast.LENGTH_LONG
                    ).show()
                }

            } catch (e: Throwable) {
                runOnUiThread {
                    binding.tvStatus.text =
                        "Ошибка автотрекинга: ${e.message}"

                    Toast.makeText(
                        this,
                        "Автотрекинг: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } finally {
                try {
                    retriever.release()
                } catch (_: Throwable) {
                }

                runOnUiThread {
                    binding.btnAutoTrack.isEnabled = true
                }
            }
        }.start()
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
        val deleteInternal =
            binding.cbDeleteInternal.isChecked

        Thread {
            val saved =
                saveToPublicVideos(
                    outputPath,
                    deleteInternal
                )

            runOnUiThread {
                binding.btnApply.isEnabled = true

                if (saved) {
                    binding.tvStatus.text = "Готово — сохранено в Видео"
                    Toast.makeText(
                        this,
                        "Готовое видео добавлено в раздел Видео",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    binding.tvStatus.text = "Готово, но не удалось добавить в галерею"
                    Toast.makeText(
                        this,
                        "Основной файл сохранён, но копия в Видео не создана",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    private fun saveToPublicVideos(outputPath: String, deleteInternal: Boolean): Boolean {
        val source = File(outputPath)

        if (!source.exists() || source.length() == 0L) {
            return false
        }

        return try {
            val fileName =
                "watermark_removed_${System.currentTimeMillis()}.mp4"

            val values = ContentValues().apply {
                put(
                    MediaStore.Video.Media.DISPLAY_NAME,
                    fileName
                )
                put(
                    MediaStore.Video.Media.MIME_TYPE,
                    "video/mp4"
                )

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(
                        MediaStore.Video.Media.RELATIVE_PATH,
                        "${Environment.DIRECTORY_MOVIES}/WatermarkRemover"
                    )
                    put(
                        MediaStore.Video.Media.IS_PENDING,
                        1
                    )
                }
            }

            val collection =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Video.Media.getContentUri(
                        MediaStore.VOLUME_EXTERNAL_PRIMARY
                    )
                } else {
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                }

            val uri =
                contentResolver.insert(collection, values)
                    ?: return false

            try {
                contentResolver.openOutputStream(uri)?.use { output ->
                    source.inputStream().use { input ->
                        input.copyTo(output)
                    }
                } ?: throw IllegalStateException(
                    "Не удалось открыть файл назначения"
                )

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val finished = ContentValues().apply {
                        put(MediaStore.Video.Media.IS_PENDING, 0)
                    }

                    contentResolver.update(
                        uri,
                        finished,
                        null,
                        null
                    )
                }

                // ВАЖНО:
                // исходный обработанный файл НЕ удаляем.
                // Остаётся и оригинальная копия приложения,
                // и копия в системном разделе Видео.

                if (deleteInternal) {
                    source.delete()
                }

                true
            } catch (e: Exception) {
                contentResolver.delete(uri, null, null)
                false
            }
        } catch (e: Exception) {
            false
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
