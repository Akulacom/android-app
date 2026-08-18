from pathlib import Path

path = Path('app/src/main/java/com/akula/watermarkremover/VideoProcessor.kt')
text = path.read_text(encoding='utf-8')

old_call = '''                    createMaskPng(
                        file = maskFile,
                        width = videoSize.first,
                        height = videoSize.second,
                        rect = run.rect
                    )'''
new_call = '''                    createContentAwareMaskPng(
                        inputPath = inputPath,
                        timeMs = (run.startMs + run.endMs) / 2L,
                        file = maskFile,
                        width = videoSize.first,
                        height = videoSize.second,
                        rect = run.rect
                    )'''
if old_call not in text:
    raise SystemExit('createMaskPng call not found')
text = text.replace(old_call, new_call, 1)

old_expand = '''        val padX = maxOf(7f, source.width() * 0.18f)
        val padY = maxOf(5f, source.height() * 0.30f)'''
new_expand = '''        // OCR-рамка уже имеет запас. Второе большое расширение раньше превращало
        // небольшую надпись в заметный прямоугольник восстановления.
        val padX = maxOf(2f, source.width() * 0.04f)
        val padY = maxOf(2f, source.height() * 0.08f)'''
if old_expand not in text:
    raise SystemExit('expandRepairRect padding not found')
text = text.replace(old_expand, new_expand, 1)

old_safe = '''        val padX = maxOf(10f, boxW * 0.30f)
        val padY = maxOf(8f, boxH * 0.55f)'''
new_safe = '''        // Небольшой запас закрывает ореол букв, но не захватывает большой кусок фона.
        val padX = maxOf(4f, boxW * 0.14f)
        val padY = maxOf(3f, boxH * 0.22f)'''
if old_safe not in text:
    raise SystemExit('toSafeVideoRect padding not found')
text = text.replace(old_safe, new_safe, 1)

start_marker = '    private fun createMaskPng(\n'
end_marker = '    private fun escapeFilterPath(path: String): String {\n'
start = text.find(start_marker)
end = text.find(end_marker, start)
if start < 0 or end < 0:
    raise SystemExit('mask function boundaries not found')

new_function = r'''    /**
     * Строит не сплошной прямоугольник, а маску самих пикселей watermark.
     * Это критично для removelogo: чем меньше здорового фона попадает в маску,
     * тем меньше прямоугольных полос и размазывания остаётся после восстановления.
     */
    private fun createContentAwareMaskPng(
        inputPath: String,
        timeMs: Long,
        file: File,
        width: Int,
        height: Int,
        rect: RectF
    ) {
        val retriever = MediaMetadataRetriever()
        var frame: Bitmap? = null
        try {
            retriever.setDataSource(inputPath)
            frame = retriever.getFrameAtTime(
                timeMs * 1000L,
                MediaMetadataRetriever.OPTION_CLOSEST
            )

            if (frame == null || frame.width != width || frame.height != height) {
                createConservativeFallbackMask(file, width, height, rect)
                return
            }

            val left = rect.left.toInt().coerceIn(1, width - 2)
            val top = rect.top.toInt().coerceIn(1, height - 2)
            val right = rect.right.toInt().coerceIn(left + 1, width - 1)
            val bottom = rect.bottom.toInt().coerceIn(top + 1, height - 1)
            val rw = right - left
            val rh = bottom - top
            val regionArea = rw * rh

            if (regionArea < 16) {
                createConservativeFallbackMask(file, width, height, rect)
                return
            }

            val region = IntArray(regionArea)
            frame.getPixels(region, 0, rw, left, top, rw, rh)

            var lumaSum = 0L
            for (pixel in region) {
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                lumaSum += ((77 * r + 150 * g + 29 * b) shr 8)
            }
            val meanLuma = (lumaSum / regionArea).toInt()

            fun saturation255(pixel: Int): Int {
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                val max = maxOf(r, g, b)
                val min = minOf(r, g, b)
                return if (max == 0) 0 else ((max - min) * 255 / max)
            }

            fun luma(pixel: Int): Int {
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                return ((77 * r + 150 * g + 29 * b) shr 8)
            }

            var brightCount = 0
            var darkCount = 0
            for (pixel in region) {
                val y = luma(pixel)
                val sat = saturation255(pixel)
                if (y >= maxOf(170, meanLuma + 20) && sat <= 105) brightCount++
                if (y <= minOf(85, meanLuma - 24) && sat <= 95) darkCount++
            }

            val preferBright = brightCount >= darkCount
            val raw = BooleanArray(regionArea)
            var selected = 0

            for (y in 0 until rh) {
                for (x in 0 until rw) {
                    val i = y * rw + x
                    val pixel = region[i]
                    val lum = luma(pixel)
                    val sat = saturation255(pixel)

                    val primary = if (preferBright) {
                        lum >= maxOf(165, meanLuma + 17) && sat <= 115
                    } else {
                        lum <= minOf(92, meanLuma - 20) && sat <= 105
                    }

                    val veryBright = lum >= 218 && sat <= 130
                    val veryDark = lum <= 42 && sat <= 105
                    val candidate = primary || veryBright || veryDark
                    raw[i] = candidate
                    if (candidate) selected++
                }
            }

            val minUseful = maxOf(6, regionArea / 1200)
            val maxUseful = regionArea * 38 / 100
            if (selected < minUseful || selected > maxUseful) {
                // Повторный, более строгий проход. Лучше оставить несколько букв,
                // чем снова замаскировать весь прямоугольник и получить полосу.
                selected = 0
                for (i in raw.indices) raw[i] = false
                for (y in 0 until rh) {
                    for (x in 0 until rw) {
                        val i = y * rw + x
                        val pixel = region[i]
                        val lum = luma(pixel)
                        val sat = saturation255(pixel)
                        val candidate = if (preferBright) {
                            lum >= maxOf(190, meanLuma + 28) && sat <= 90
                        } else {
                            lum <= minOf(68, meanLuma - 30) && sat <= 80
                        }
                        raw[i] = candidate
                        if (candidate) selected++
                    }
                }
            }

            if (selected < minUseful) {
                createConservativeFallbackMask(file, width, height, rect)
                return
            }

            val maskPixels = IntArray(width * height) { Color.BLACK }
            val radius = 2
            for (y in 0 until rh) {
                for (x in 0 until rw) {
                    if (!raw[y * rw + x]) continue
                    val gx = left + x
                    val gy = top + y
                    for (dy in -radius..radius) {
                        val py = gy + dy
                        if (py !in 0 until height) continue
                        for (dx in -radius..radius) {
                            if (dx * dx + dy * dy > radius * radius + 1) continue
                            val px = gx + dx
                            if (px !in 0 until width) continue
                            maskPixels[py * width + px] = Color.WHITE
                        }
                    }
                }
            }

            val mask = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                mask.setPixels(maskPixels, 0, width, 0, 0, width, height)
                file.outputStream().use { output ->
                    if (!mask.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                        throw IllegalStateException("Не удалось создать точную mask PNG")
                    }
                }
            } finally {
                mask.recycle()
            }
        } catch (_: Throwable) {
            createConservativeFallbackMask(file, width, height, rect)
        } finally {
            try { frame?.recycle() } catch (_: Throwable) {}
            try { retriever.release() } catch (_: Throwable) {}
        }
    }

    private fun createConservativeFallbackMask(
        file: File,
        width: Int,
        height: Int,
        rect: RectF
    ) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.BLACK)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.FILL
            }

            // При невозможности построить пиксельную маску используем уменьшенную,
            // а не расширенную OCR-рамку. Это безопаснее для фона.
            val insetX = rect.width() * 0.08f
            val insetY = rect.height() * 0.12f
            val safe = RectF(
                rect.left + insetX,
                rect.top + insetY,
                rect.right - insetX,
                rect.bottom - insetY
            )
            val radius = minOf(safe.width(), safe.height()) * 0.18f
            canvas.drawRoundRect(safe, radius, radius, paint)

            file.outputStream().use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    throw IllegalStateException("Не удалось создать fallback mask PNG")
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

'''

text = text[:start] + new_function + text[end:]
path.write_text(text, encoding='utf-8')
print('VideoProcessor.kt patched successfully')
