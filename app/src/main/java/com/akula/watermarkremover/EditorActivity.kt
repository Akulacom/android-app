package com.akula.watermarkremover

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.akula.watermarkremover.databinding.ActivityEditorBinding
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
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

    private data class ZoneCandidate(
        val timeMs: Long,
        val rect: Rect,
        val zoneId: String,
        val zoneScore: Float
    )

    private data class OcrCandidate(
        val timeMs: Long,
        val key: String,
        val rect: RectF,
        val text: String
    )

    private data class OcrGroup(
        val key: String,
        val items: MutableList<OcrCandidate> = mutableListOf()
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
            binding.maskOverlay.maskRect.setEmpty()
            binding.maskOverlay.invalidate()
            updateKeyframeLabel()
            Toast.makeText(this, "Точки трекинга сброшены", Toast.LENGTH_SHORT).show()
        }

        binding.btnAutoTrack.setOnClickListener {
            val manualRect = currentMaskInVideoCoords()
            startAutoTracking(manualRect, autoProcessAfter = false)
        }

        val prefs = getSharedPreferences("watermark_settings", MODE_PRIVATE)
        binding.cbDeleteInternal.isChecked = prefs.getBoolean("delete_internal", false)
        binding.cbDeleteInternal.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("delete_internal", checked).apply()
        }

        binding.btnApply.setOnClickListener {
            try {
                binding.videoView.pause()
                onApplyClicked()
            } catch (t: Throwable) {
                binding.btnApply.isEnabled = true
                binding.progressBar.visibility = android.view.View.INVISIBLE
                binding.progressBar.isIndeterminate = false
                val msg = "Ошибка запуска: ${t.javaClass.simpleName}: ${t.message}"
                binding.tvStatus.text = msg
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        }
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
        if (!binding.maskOverlay.hasMask() || videoWidth <= 0 || videoHeight <= 0) return null

        val overlay = binding.maskOverlay
        val video = binding.videoView

        val viewW = video.width.toFloat()
        val viewH = video.height.toFloat()
        if (viewW <= 0f || viewH <= 0f) return null

        val videoLocation = IntArray(2)
        val overlayLocation = IntArray(2)
        video.getLocationOnScreen(videoLocation)
        overlay.getLocationOnScreen(overlayLocation)

        val videoViewLeft = (videoLocation[0] - overlayLocation[0]).toFloat()
        val videoViewTop = (videoLocation[1] - overlayLocation[1]).toFloat()

        val sourceAspect = videoWidth.toFloat() / videoHeight.toFloat()
        val viewAspect = viewW / viewH

        val contentW: Float
        val contentH: Float
        val contentLeft: Float
        val contentTop: Float

        if (viewAspect > sourceAspect) {
            contentH = viewH
            contentW = viewH * sourceAspect
            contentLeft = videoViewLeft + (viewW - contentW) / 2f
            contentTop = videoViewTop
        } else {
            contentW = viewW
            contentH = viewW / sourceAspect
            contentLeft = videoViewLeft
            contentTop = videoViewTop + (viewH - contentH) / 2f
        }

        val sourceRect = overlay.maskRect
        val contentRight = contentLeft + contentW
        val contentBottom = contentTop + contentH

        val left = sourceRect.left.coerceIn(contentLeft, contentRight - 2f)
        val top = sourceRect.top.coerceIn(contentTop, contentBottom - 2f)
        val right = sourceRect.right.coerceIn(left + 2f, contentRight)
        val bottom = sourceRect.bottom.coerceIn(top + 2f, contentBottom)

        val scaleX = videoWidth.toFloat() / contentW
        val scaleY = videoHeight.toFloat() / contentH

        return RectF(
            ((left - contentLeft) * scaleX).coerceIn(0f, videoWidth.toFloat() - 2f),
            ((top - contentTop) * scaleY).coerceIn(0f, videoHeight.toFloat() - 2f),
            ((right - contentLeft) * scaleX).coerceIn(2f, videoWidth.toFloat()),
            ((bottom - contentTop) * scaleY).coerceIn(2f, videoHeight.toFloat())
        )
    }

    private fun grayValue(pixel: Int): Int {
        return (Color.red(pixel) * 30 + Color.green(pixel) * 59 + Color.blue(pixel) * 11) / 100
    }

    private fun bitmapToGray(bitmap: Bitmap): IntArray {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
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

        val sampleStep = maxOf(1, minOf(width, height) / 12)

        for (y in 1 until height - 1 step sampleStep) {
            for (x in 1 until width - 1 step sampleStep) {
                val index = y * width + x
                val gx = gray[index + 1] - gray[index - 1]
                val gy = gray[index + width] - gray[index - width]
                val strength = kotlin.math.abs(gx) + kotlin.math.abs(gy)
                if (strength >= 18) {
                    xs.add(x)
                    ys.add(y)
                    gxList.add(gx)
                    gyList.add(gy)
                }
            }
        }

        if (xs.size < 15) {
            xs.clear()
            ys.clear()
            gxList.clear()
            gyList.clear()

            val fallbackStep = maxOf(1, minOf(width, height) / 10)
            for (y in 1 until height - 1 step fallbackStep) {
                for (x in 1 until width - 1 step fallbackStep) {
                    val index = y * width + x
                    xs.add(x)
                    ys.add(y)
                    gxList.add(gray[index + 1] - gray[index - 1])
                    gyList.add(gray[index + width] - gray[index - width])
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

    private fun findWatermarkOnFrame(bitmap: Bitmap, template: TrackerTemplate): Pair<Rect, Float> {
        val width = bitmap.width
        val height = bitmap.height
        val gray = bitmapToGray(bitmap)

        val maxX = width - template.width
        val maxY = height - template.height

        if (maxX <= 0 || maxY <= 0) {
            return Pair(
                Rect(0, 0, template.width.coerceAtMost(width), template.height.coerceAtMost(height)),
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
                val gx = gray[index + 1] - gray[index - 1]
                val gy = gray[index + width] - gray[index - width]
                total += kotlin.math.abs(gx - template.gx[i]) + kotlin.math.abs(gy - template.gy[i])
            }
            return if (template.xs.isEmpty()) Float.MAX_VALUE else total.toFloat() / (template.xs.size * 2f)
        }

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

        val refineLeft = (bestX - 4).coerceAtLeast(0)
        val refineRight = (bestX + 4).coerceAtMost(maxX)
        val refineTop = (bestY - 4).coerceAtLeast(0)
        val refineBottom = (bestY + 4).coerceAtMost(maxY)

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
            Rect(bestX, bestY, bestX + template.width, bestY + template.height),
            bestScore
        )
    }

    private fun sampleTimelineTimes(): LongArray {
        val d = videoDurationMs.coerceAtLeast(1L)
        val points = longArrayOf(
            0L,
            d / 10,
            d / 4,
            d / 2,
            d * 3 / 4,
            d * 9 / 10
        )
        return points.distinct().sorted().toLongArray()
    }

    private fun buildScaledFrame(
        retriever: MediaMetadataRetriever,
        timeMs: Long,
        targetWidth: Int
    ): Bitmap? {
        val frame = retriever.getFrameAtTime(
            timeMs * 1000L,
            MediaMetadataRetriever.OPTION_CLOSEST
        ) ?: return null

        val targetHeight = maxOf(
            1,
            (frame.height * targetWidth.toFloat() / frame.width.toFloat()).toInt()
        )
        return Bitmap.createScaledBitmap(frame, targetWidth, targetHeight, true)
    }

    private fun detectCandidatesOnFrame(bitmap: Bitmap, timeMs: Long): List<ZoneCandidate> {
        val width = bitmap.width
        val height = bitmap.height

        val zoneW = (width * 0.38f).toInt().coerceAtLeast(40)
        val zoneH = (height * 0.28f).toInt().coerceAtLeast(30)
        val centerLeft = ((width - zoneW) / 2).coerceAtLeast(0)

        val zones = listOf(
            intArrayOf(0, 0, zoneW, zoneH),
            intArrayOf(width - zoneW, 0, width, zoneH),
            intArrayOf(0, height - zoneH, zoneW, height),
            intArrayOf(width - zoneW, height - zoneH, width, height),
            intArrayOf(centerLeft, 0, (centerLeft + zoneW).coerceAtMost(width), zoneH),
            intArrayOf(centerLeft, height - zoneH, (centerLeft + zoneW).coerceAtMost(width), height)
        )
        val zoneNames = listOf("tl", "tr", "bl", "br", "tc", "bc")

        val results = ArrayList<ZoneCandidate>()
        for (i in zones.indices) {
            val z = zones[i]
            val candidate = detectCandidateInZone(bitmap, timeMs, z[0], z[1], z[2], z[3], zoneNames[i])
            if (candidate != null) results.add(candidate)
        }
        return results
    }

    private fun detectCandidateInZone(
        bitmap: Bitmap,
        timeMs: Long,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        zoneId: String
    ): ZoneCandidate? {
        val zoneW = right - left
        val zoneH = bottom - top
        if (zoneW < 10 || zoneH < 10) return null

        val pixels = IntArray(zoneW * zoneH)
        bitmap.getPixels(pixels, 0, zoneW, left, top, zoneW, zoneH)

        val mask = BooleanArray(zoneW * zoneH)
        fun idx(x: Int, y: Int) = y * zoneW + x

        for (y in 1 until zoneH - 1) {
            for (x in 1 until zoneW - 1) {
                val p = pixels[idx(x, y)]
                val lum = grayValue(p)
                val r = Color.red(p)
                val g = Color.green(p)
                val b = Color.blue(p)
                val saturation = maxOf(r, maxOf(g, b)) - minOf(r, minOf(g, b))

                val lumRight = grayValue(pixels[idx(x + 1, y)])
                val lumLeft = grayValue(pixels[idx(x - 1, y)])
                val lumUp = grayValue(pixels[idx(x, y - 1)])
                val lumDown = grayValue(pixels[idx(x, y + 1)])
                val edge = kotlin.math.abs(lumRight - lumLeft) + kotlin.math.abs(lumDown - lumUp)

                val isCandidate =
                    edge >= 48 && (
                        lum >= 165 ||
                        lum <= 95 ||
                        saturation >= 55
                    )

                if (isCandidate) {
                    mask[idx(x, y)] = true
                }
            }
        }

        val visited = BooleanArray(zoneW * zoneH)
        val queue = IntArray(zoneW * zoneH)

        var bestRect: Rect? = null
        var bestScore = 0f
        val zoneArea = zoneW * zoneH

        for (startY in 0 until zoneH) {
            for (startX in 0 until zoneW) {
                val startIndex = idx(startX, startY)
                if (!mask[startIndex] || visited[startIndex]) continue

                var head = 0
                var tail = 0
                queue[tail++] = startIndex
                visited[startIndex] = true

                var count = 0
                var minX = startX
                var maxX = startX
                var minY = startY
                var maxY = startY

                while (head < tail) {
                    val current = queue[head++]
                    val cy = current / zoneW
                    val cx = current % zoneW
                    count++

                    if (cx < minX) minX = cx
                    if (cx > maxX) maxX = cx
                    if (cy < minY) minY = cy
                    if (cy > maxY) maxY = cy

                    val neighbors = intArrayOf(
                        current - 1,
                        current + 1,
                        current - zoneW,
                        current + zoneW
                    )

                    for (n in neighbors) {
                        if (n < 0 || n >= mask.size || visited[n] || !mask[n]) continue
                        val ny = n / zoneW
                        val nx = n % zoneW
                        if (kotlin.math.abs(nx - cx) + kotlin.math.abs(ny - cy) != 1) continue
                        visited[n] = true
                        queue[tail++] = n
                    }
                }

                val compW = maxX - minX + 1
                val compH = maxY - minY + 1
                val bboxArea = compW * compH
                if (count < 10) continue
                if (compW < 10 || compH < 8) continue
                if (bboxArea <= 0 || bboxArea > zoneArea / 2) continue

                val density = count.toFloat() / bboxArea.toFloat()
                val borderTouch =
                    minX <= 6 || minY <= 6 || maxX >= zoneW - 7 || maxY >= zoneH - 7
                val relativeArea = bboxArea.toFloat() / zoneArea.toFloat()
                val sizePenalty = if (relativeArea > 0.22f) relativeArea * 0.7f else relativeArea * 0.2f
                val score = density + if (borderTouch) 0.25f else 0f - sizePenalty

                if (score > bestScore) {
                    val pad = 4
                    bestScore = score
                    bestRect = Rect(
                        (left + minX - pad).coerceAtLeast(0),
                        (top + minY - pad).coerceAtLeast(0),
                        (left + maxX + 1 + pad).coerceAtMost(bitmap.width),
                        (top + maxY + 1 + pad).coerceAtMost(bitmap.height)
                    )
                }
            }
        }

        val finalRect = bestRect ?: return null
        if (bestScore < 0.18f) return null

        return ZoneCandidate(
            timeMs = timeMs,
            rect = finalRect,
            zoneId = zoneId,
            zoneScore = bestScore
        )
    }

    private fun normalizeWatermarkText(value: String): String {
        return value
            .lowercase()
            .filter { it.isLetterOrDigit() }
            .replace('0', 'o')
            .replace('1', 'i')
            .replace('l', 'i')
    }

    private fun editDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)

        for (i in a.indices) {
            current[0] = i + 1
            for (j in b.indices) {
                val cost = if (a[i] == b[j]) 0 else 1
                current[j + 1] = minOf(
                    current[j] + 1,
                    previous[j + 1] + 1,
                    previous[j] + cost
                )
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }

    private fun sameWatermarkKey(a: String, b: String): Boolean {
        if (a == b) return true
        if (a.length >= 3 && b.length >= 3 && (a.contains(b) || b.contains(a))) return true
        val minLen = minOf(a.length, b.length)
        if (minLen < 3) return false
        val allowed = maxOf(1, minLen / 4)
        return editDistance(a, b) <= allowed
    }

    private fun isLikelyOverlayRect(rect: android.graphics.Rect, frameW: Int, frameH: Int): Boolean {
        if (rect.width() < 8 || rect.height() < 6) return false
        if (rect.width() > frameW * 0.55f || rect.height() > frameH * 0.25f) return false

        val areaRatio = (rect.width().toFloat() * rect.height().toFloat()) /
            (frameW.toFloat() * frameH.toFloat())
        if (areaRatio > 0.08f) return false

        val cx = rect.exactCenterX() / frameW.toFloat()
        val cy = rect.exactCenterY() / frameH.toFloat()

        // Watermark обычно держится ближе к краям. Боковые зоны разрешаем
        // по всей высоте, чтобы ловить Dola AI слева посередине.
        return cx <= 0.38f || cx >= 0.62f || cy <= 0.22f || cy >= 0.78f
    }

    private fun paddedVideoRect(source: android.graphics.Rect, frameW: Int, frameH: Int): RectF {
        val padX = maxOf(8f, source.width() * 0.22f)
        val padY = maxOf(6f, source.height() * 0.32f)

        val left = (source.left - padX).coerceAtLeast(0f)
        val top = (source.top - padY).coerceAtLeast(0f)
        val right = (source.right + padX).coerceAtMost(frameW.toFloat())
        val bottom = (source.bottom + padY).coerceAtMost(frameH.toFloat())

        val scaleX = videoWidth.toFloat() / frameW.toFloat()
        val scaleY = videoHeight.toFloat() / frameH.toFloat()

        return RectF(
            left * scaleX,
            top * scaleY,
            right * scaleX,
            bottom * scaleY
        )
    }

    /**
     * Настоящий авто-режим для текстовых watermark:
     * 1) сканирует весь ролик, а не один кадр;
     * 2) OCR находит повторяющийся текст у краёв кадра;
     * 3) одинаковый watermark группируется даже при небольших OCR-ошибках;
     * 4) когда watermark пропал, создаётся active=false;
     * 5) когда он мгновенно прыгнул после склейки, новая позиция применяется
     *    сразу, без линейной поездки маски через весь кадр.
     */
    private fun buildOcrAutoKeyframes(retriever: MediaMetadataRetriever): List<MaskKeyframe> {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val stepMs = when {
                videoDurationMs <= 20_000L -> 350L
                videoDurationMs <= 60_000L -> 500L
                else -> 750L
            }

            val sampleTimes = mutableListOf<Long>()
            var t = 0L
            while (t <= videoDurationMs) {
                sampleTimes.add(t)
                t += stepMs
            }
            if (sampleTimes.isEmpty() || sampleTimes.last() < videoDurationMs) {
                sampleTimes.add(videoDurationMs)
            }

            val candidatesByTime = linkedMapOf<Long, MutableList<OcrCandidate>>()
            val groups = mutableListOf<OcrGroup>()

            for ((index, timeMs) in sampleTimes.withIndex()) {
                val frame = retriever.getFrameAtTime(
                    timeMs * 1000L,
                    MediaMetadataRetriever.OPTION_CLOSEST
                ) ?: continue

                val ocrFrame = if (frame.width < 900) {
                    val scale = 1.6f
                    Bitmap.createScaledBitmap(
                        frame,
                        (frame.width * scale).toInt(),
                        (frame.height * scale).toInt(),
                        true
                    )
                } else {
                    frame
                }

                val image = InputImage.fromBitmap(ocrFrame, 0)
                val result = Tasks.await(recognizer.process(image))
                val frameCandidates = mutableListOf<OcrCandidate>()

                for (block in result.textBlocks) {
                    for (line in block.lines) {
                        val box = line.boundingBox ?: continue
                        val key = normalizeWatermarkText(line.text)
                        if (key.length < 2 || key.length > 40) continue
                        if (!isLikelyOverlayRect(box, ocrFrame.width, ocrFrame.height)) continue

                        val candidate = OcrCandidate(
                            timeMs = timeMs,
                            key = key,
                            rect = paddedVideoRect(box, ocrFrame.width, ocrFrame.height),
                            text = line.text
                        )
                        frameCandidates.add(candidate)

                        var group = groups.firstOrNull { sameWatermarkKey(it.key, key) }
                        if (group == null) {
                            group = OcrGroup(key)
                            groups.add(group)
                        }
                        group.items.add(candidate)
                    }
                }

                candidatesByTime[timeMs] = frameCandidates

                if (ocrFrame !== frame) ocrFrame.recycle()
                frame.recycle()

                val percent = (((index + 1).toFloat() / sampleTimes.size.toFloat()) * 100f)
                    .toInt().coerceIn(0, 100)
                runOnUiThread {
                    binding.tvStatus.text = "Поиск watermark: $percent%"
                }
            }

            val validGroups = groups
                .filter { it.items.size >= 2 }
                .sortedByDescending { it.items.size }

            if (validGroups.isEmpty()) {
                throw IllegalStateException(
                    "Повторяющийся текстовый watermark не найден"
                )
            }

            // Берём все устойчивые повторяющиеся подписи, но не более трёх.
            // Это позволяет одному ролику иметь разные watermark в разных сценах.
            val accepted = validGroups.take(3)

            fun groupRank(candidate: OcrCandidate): Int {
                val index = accepted.indexOfFirst { sameWatermarkKey(it.key, candidate.key) }
                return if (index < 0) Int.MAX_VALUE else index
            }

            val resultFrames = mutableListOf<MaskKeyframe>()
            for (timeMs in sampleTimes) {
                val choices = candidatesByTime[timeMs]
                    .orEmpty()
                    .filter { groupRank(it) != Int.MAX_VALUE }
                    .sortedWith(
                        compareBy<OcrCandidate> { groupRank(it) }
                            .thenBy { it.rect.width() * it.rect.height() }
                    )

                val chosen = choices.firstOrNull()
                if (chosen == null) {
                    resultFrames.add(MaskKeyframe(timeMs, RectF(), active = false))
                } else {
                    resultFrames.add(MaskKeyframe(timeMs, chosen.rect, active = true))
                }
            }

            // Закрываем единичные OCR-пропуски между двумя близкими позициями.
            // Длинные промежутки остаются inactive — там watermark не трогаем.
            for (i in 1 until resultFrames.size - 1) {
                val current = resultFrames[i]
                if (current.active) continue

                val prev = resultFrames[i - 1]
                val next = resultFrames[i + 1]
                if (!prev.active || !next.active) continue

                val prevCx = prev.rect.centerX()
                val prevCy = prev.rect.centerY()
                val nextCx = next.rect.centerX()
                val nextCy = next.rect.centerY()
                val distance = kotlin.math.hypot(nextCx - prevCx, nextCy - prevCy)
                val diagonal = kotlin.math.hypot(videoWidth.toFloat(), videoHeight.toFloat())

                if (distance <= diagonal * 0.12f) {
                    resultFrames[i] = MaskKeyframe(
                        current.timeMs,
                        RectF(
                            (prev.rect.left + next.rect.left) / 2f,
                            (prev.rect.top + next.rect.top) / 2f,
                            (prev.rect.right + next.rect.right) / 2f,
                            (prev.rect.bottom + next.rect.bottom) / 2f
                        ),
                        active = true
                    )
                }
            }

            val activeCount = resultFrames.count { it.active }
            if (activeCount < 2) {
                throw IllegalStateException("Watermark найден слишком неуверенно")
            }

            return resultFrames
        } finally {
            recognizer.close()
        }
    }

    private fun detectAutomaticSeed(
        retriever: MediaMetadataRetriever,
        trackingWidth: Int
    ): Pair<Long, RectF>? {
        val sampleTimes = sampleTimelineTimes()
        if (sampleTimes.isEmpty()) return null

        val frames = LinkedHashMap<Long, Bitmap>()
        for (time in sampleTimes) {
            val scaled = buildScaledFrame(retriever, time, trackingWidth)
            if (scaled != null) {
                frames[time] = scaled
            }
        }
        if (frames.isEmpty()) return null

        val pool = ArrayList<Pair<ZoneCandidate, Bitmap>>()
        for ((time, bmp) in frames) {
            val found = detectCandidatesOnFrame(bmp, time)
                .sortedByDescending { it.zoneScore }
                .take(2)
            for (candidate in found) {
                pool.add(candidate to bmp)
            }
        }

        if (pool.isEmpty()) return null

        val topPool = pool.sortedByDescending { it.first.zoneScore }.take(8)

        var bestTime = -1L
        var bestRect: Rect? = null
        var bestValidation = Float.MAX_VALUE

        for ((candidate, sourceFrame) in topPool) {
            val rect = candidate.rect
            val safeW = rect.width().coerceAtMost(sourceFrame.width - rect.left)
            val safeH = rect.height().coerceAtMost(sourceFrame.height - rect.top)
            if (safeW < 8 || safeH < 8) continue

            val templateBitmap = Bitmap.createBitmap(
                sourceFrame,
                rect.left,
                rect.top,
                safeW,
                safeH
            )
            val template = buildTrackerTemplate(templateBitmap)
            if (template.xs.isEmpty()) continue

            var totalScore = 0f
            var used = 0

            for ((frameTime, frame) in frames) {
                if (frameTime == candidate.timeMs) continue
                val result = findWatermarkOnFrame(frame, template)
                totalScore += result.second
                used++
            }

            if (used == 0) continue

            val averageScore = totalScore / used.toFloat()
            val finalScore = averageScore - candidate.zoneScore * 8f

            if (finalScore < bestValidation) {
                bestValidation = finalScore
                bestTime = candidate.timeMs
                bestRect = rect
            }
        }

        if (bestTime < 0 || bestRect == null) return null
        if (bestValidation > 85f) return null

        val sampleHeight = frames[bestTime]?.height ?: return null

        val rectVideo = RectF(
            bestRect.left.toFloat() / trackingWidth.toFloat() * videoWidth.toFloat(),
            bestRect.top.toFloat() / sampleHeight.toFloat() * videoHeight.toFloat(),
            bestRect.right.toFloat() / trackingWidth.toFloat() * videoWidth.toFloat(),
            bestRect.bottom.toFloat() / sampleHeight.toFloat() * videoHeight.toFloat()
        )

        return bestTime to rectVideo
    }

    private fun buildTrackedKeyframes(
        retriever: MediaMetadataRetriever,
        referenceTime: Long,
        sourceRect: RectF,
        trackingWidth: Int
    ): List<MaskKeyframe> {
        val referenceFrame = buildScaledFrame(retriever, referenceTime, trackingWidth)
            ?: throw IllegalStateException("Не удалось получить исходный кадр")

        val left = (sourceRect.left / videoWidth.toFloat() * referenceFrame.width).toInt()
            .coerceIn(0, referenceFrame.width - 2)
        val top = (sourceRect.top / videoHeight.toFloat() * referenceFrame.height).toInt()
            .coerceIn(0, referenceFrame.height - 2)
        val right = (sourceRect.right / videoWidth.toFloat() * referenceFrame.width).toInt()
            .coerceIn(left + 2, referenceFrame.width)
        val bottom = (sourceRect.bottom / videoHeight.toFloat() * referenceFrame.height).toInt()
            .coerceIn(top + 2, referenceFrame.height)

        val templateBitmap = Bitmap.createBitmap(
            referenceFrame,
            left,
            top,
            right - left,
            bottom - top
        )

        val template = buildTrackerTemplate(templateBitmap)
        if (template.xs.isEmpty()) {
            throw IllegalStateException("Не удалось создать шаблон watermark")
        }

        val tracked = mutableListOf<MaskKeyframe>()
        val stepMs = when {
            videoDurationMs <= 15_000L -> 400L
            videoDurationMs <= 30_000L -> 600L
            else -> 1000L
        }

        var timeMs = 0L
        while (timeMs <= videoDurationMs) {
            val frame = buildScaledFrame(retriever, timeMs, trackingWidth)
            if (frame != null) {
                val result = findWatermarkOnFrame(frame, template)
                val found = result.first
                val rect = RectF(
                    found.left.toFloat() / frame.width.toFloat() * videoWidth.toFloat(),
                    found.top.toFloat() / frame.height.toFloat() * videoHeight.toFloat(),
                    found.right.toFloat() / frame.width.toFloat() * videoWidth.toFloat(),
                    found.bottom.toFloat() / frame.height.toFloat() * videoHeight.toFloat()
                )
                tracked.add(MaskKeyframe(timeMs, rect))
            }

            val percent = (timeMs.toFloat() / videoDurationMs.toFloat() * 100f).toInt().coerceIn(0, 100)
            runOnUiThread {
                binding.tvStatus.text = "Автотрекинг: $percent%"
            }

            timeMs += stepMs
        }

        if (tracked.isEmpty()) {
            throw IllegalStateException("Watermark не найден")
        }

        if (tracked.last().timeMs < videoDurationMs) {
            tracked.add(MaskKeyframe(videoDurationMs, RectF(tracked.last().rect)))
        }

        return tracked
    }

    private fun startAutoTracking(manualRect: RectF?, autoProcessAfter: Boolean) {
        if (videoWidth <= 0 || videoHeight <= 0 || videoDurationMs <= 0) {
            Toast.makeText(this, "Не удалось получить параметры видео", Toast.LENGTH_LONG).show()
            return
        }

        binding.videoView.pause()
        binding.btnAutoTrack.isEnabled = false
        binding.btnApply.isEnabled = false
        binding.progressBar.visibility = android.view.View.VISIBLE
        binding.progressBar.isIndeterminate = true
        binding.tvStatus.text = if (manualRect != null) {
            "Автотрекинг по выделенной области..."
        } else {
            "Автопоиск watermark по всему видео..."
        }

        Thread {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(this, videoUri)
                val trackingWidth = 360

                val tracked = if (manualRect == null) {
                    buildOcrAutoKeyframes(retriever)
                } else {
                    val seed = binding.videoView.currentPosition.toLong() to manualRect
                    buildTrackedKeyframes(
                        retriever = retriever,
                        referenceTime = seed.first,
                        sourceRect = seed.second,
                        trackingWidth = trackingWidth
                    )
                }

                runOnUiThread {
                    keyframes.clear()
                    keyframes.addAll(tracked)
                    updateKeyframeLabel()
                    binding.progressBar.isIndeterminate = false

                    if (autoProcessAfter) {
                        binding.tvStatus.text = "Watermark найден. Запускаю очистку..."
                        startProcessingWithCurrentKeyframes()
                    } else {
                        binding.progressBar.visibility = android.view.View.INVISIBLE
                        binding.btnApply.isEnabled = true
                        binding.tvStatus.text = "Автотрекинг готов: ${tracked.count { it.active }} активных точек"
                        val doneMessage = if (manualRect != null) {
                            "Плавающий watermark отслежен"
                        } else {
                            "Watermark найден по всему ролику"
                        }
                        Toast.makeText(this, doneMessage, Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    binding.progressBar.isIndeterminate = false
                    binding.progressBar.visibility = android.view.View.INVISIBLE
                    binding.btnApply.isEnabled = true
                    val msg = e.message ?: "Неизвестная ошибка"
                    binding.tvStatus.text = "Ошибка автотрекинга: $msg"
                    Toast.makeText(this, "Автотрекинг: $msg", Toast.LENGTH_LONG).show()
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
        keyframes.removeAll { kotlin.math.abs(it.timeMs - timeMs) < 50 }
        keyframes.add(MaskKeyframe(timeMs, rect))
        updateKeyframeLabel()
        Toast.makeText(this, "Точка добавлена на ${timeMs / 1000}с", Toast.LENGTH_SHORT).show()
    }

    private fun updateKeyframeLabel() {
        val active = keyframes.count { it.active }
        binding.tvKeyframes.text = when {
            keyframes.isEmpty() -> "Точек трекинга: 0"
            keyframes.size == 1 -> "Точек трекинга: 1 (маска статична)"
            else -> "Точек трекинга: $active активных / ${keyframes.size} проверок"
        }
    }

    private fun onApplyClicked() {
        if (keyframes.isNotEmpty()) {
            startProcessingWithCurrentKeyframes()
            return
        }

        val manualRect = currentMaskInVideoCoords()
        if (manualRect != null) {
            keyframes.add(MaskKeyframe(binding.videoView.currentPosition.toLong(), manualRect))
            startProcessingWithCurrentKeyframes()
            return
        }

        startAutoTracking(manualRect = null, autoProcessAfter = true)
    }

    private fun startProcessingWithCurrentKeyframes() {
        val inputPath = resolveRealPath(videoUri)
        if (inputPath == null) {
            Toast.makeText(this, "Не удалось получить путь к файлу", Toast.LENGTH_SHORT).show()
            binding.btnApply.isEnabled = true
            return
        }

        val outDir = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "watermark_removed")
        outDir.mkdirs()
        val outputPath = File(outDir, "clean_${System.currentTimeMillis()}.mp4").absolutePath

        binding.btnApply.isEnabled = false
        binding.progressBar.visibility = android.view.View.VISIBLE
        binding.progressBar.isIndeterminate = false
        binding.progressBar.progress = 0
        binding.tvStatus.text = "Обработка..."

        if (binding.radioQuality.isChecked) {
            val fastBasePath = File(
                outDir,
                "quality_fast_${System.currentTimeMillis()}.mp4"
            ).absolutePath

            VideoProcessor.removeWatermarkAndExport(
                inputPath = inputPath,
                outputPath = fastBasePath,
                keyframes = keyframes,
                durationMs = videoDurationMs,
                callback = object : VideoProcessor.Callback {
                    override fun onProgress(percent: Int) {
                        runOnUiThread {
                            binding.progressBar.progress = (percent * 35 / 100).coerceIn(0, 35)
                            binding.tvStatus.text = "Качество 1/2: очистка FAST — $percent%"
                        }
                    }

                    override fun onSuccess(fastPath: String) {
                        NeuralInpainter.process(
                            context = this@EditorActivity,
                            inputPath = fastPath,
                            outputPath = outputPath,
                            keyframes = keyframes,
                            videoDurationMs = videoDurationMs,
                            callback = object : NeuralInpainter.Callback {
                                override fun onProgress(percent: Int, stage: String) {
                                    runOnUiThread {
                                        binding.progressBar.progress =
                                            (35 + percent * 65 / 100).coerceIn(35, 99)
                                        binding.tvStatus.text = "Качество 2/2: $stage"
                                    }
                                }

                                override fun onSuccess(outputPath: String) {
                                    try { File(fastPath).delete() } catch (_: Throwable) {}
                                    onProcessingSuccess(outputPath)
                                }

                                override fun onError(message: String) {
                                    try { File(fastPath).delete() } catch (_: Throwable) {}
                                    onProcessingError(message)
                                }
                            }
                        )
                    }

                    override fun onError(message: String) {
                        try { File(fastBasePath).delete() } catch (_: Throwable) {}
                        onProcessingError(message)
                    }
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
        val deleteInternal = binding.cbDeleteInternal.isChecked

        Thread {
            val saved = saveToPublicVideos(outputPath, deleteInternal)
            runOnUiThread {
                binding.btnApply.isEnabled = true
                binding.progressBar.visibility = android.view.View.INVISIBLE
                binding.progressBar.isIndeterminate = false

                if (saved) {
                    binding.tvStatus.text = "Готово — сохранено в Видео"
                    Toast.makeText(this, "Готовое видео добавлено в раздел Видео", Toast.LENGTH_LONG).show()
                } else {
                    binding.tvStatus.text = "Готово, но не удалось добавить в галерею"
                    Toast.makeText(this, "Основной файл сохранён, но копия в Видео не создана", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun saveToPublicVideos(outputPath: String, deleteInternal: Boolean): Boolean {
        val source = File(outputPath)
        if (!source.exists() || source.length() == 0L) return false

        return try {
            val fileName = "watermark_removed_${System.currentTimeMillis()}.mp4"

            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(
                        MediaStore.Video.Media.RELATIVE_PATH,
                        "${Environment.DIRECTORY_MOVIES}/WatermarkRemover"
                    )
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }

            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }

            val uri = contentResolver.insert(collection, values) ?: return false

            try {
                contentResolver.openOutputStream(uri)?.use { output ->
                    source.inputStream().use { input ->
                        input.copyTo(output)
                    }
                } ?: throw IllegalStateException("Не удалось открыть файл назначения")

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val finished = ContentValues().apply {
                        put(MediaStore.Video.Media.IS_PENDING, 0)
                    }
                    contentResolver.update(uri, finished, null, null)
                }

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
            binding.progressBar.visibility = android.view.View.INVISIBLE
            binding.progressBar.isIndeterminate = false
            binding.tvStatus.text = "Ошибка: $message"
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

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
