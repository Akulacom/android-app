from pathlib import Path

root = Path(__file__).resolve().parents[1]


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        if new in text:
            print(f"SKIP {label}: already applied")
            return text
        raise SystemExit(f"ERROR {label}: target block not found")
    print(f"OK {label}")
    return text.replace(old, new, 1)


# 1) OCR verification: keep known floating watermark active across short OCR misses.
vp = root / "app/src/main/java/com/akula/watermarkremover/VideoProcessor.kt"
s = vp.read_text(encoding="utf-8")
old = '''            val maxFillGapMs = stepMs + stepMs / 2L
            val result = mutableListOf<MaskKeyframe>()

            for (timeMs in times) {
                val exact = sourceHits.firstOrNull { it.timeMs == timeMs }
                val nearest = exact ?: sourceHits.minByOrNull {
                    kotlin.math.abs(it.timeMs - timeMs)
                }

                if (nearest != null && kotlin.math.abs(nearest.timeMs - timeMs) <= maxFillGapMs) {
                    result.add(
                        MaskKeyframe(
                            timeMs = timeMs,
                            rect = RectF(nearest.rect),
                            active = true
                        )
                    )
                } else {
                    result.add(
                        MaskKeyframe(
                            timeMs = timeMs,
                            rect = RectF(),
                            active = false
                        )
                    )
                }
            }
'''
new = '''            val knownTrack = knownWatermarkBonus(chosen.group.key) > 0f
            // Known floating watermarks are translucent and OCR can miss one or
            // two checks while the logo is still visible. Bridge only short gaps.
            val nearestFillMs = if (knownTrack) stepMs * 2L else stepMs + stepMs / 2L
            val surroundedBridgeMs = if (knownTrack) stepMs * 3L else stepMs * 2L
            val result = mutableListOf<MaskKeyframe>()

            for (timeMs in times) {
                val exact = sourceHits.firstOrNull { it.timeMs == timeMs }
                val nearest = exact ?: sourceHits.minByOrNull {
                    kotlin.math.abs(it.timeMs - timeMs)
                }
                val before = sourceHits.lastOrNull { it.timeMs <= timeMs }
                val after = sourceHits.firstOrNull { it.timeMs >= timeMs }
                val surroundedMiss = before != null && after != null &&
                    after.timeMs >= before.timeMs &&
                    after.timeMs - before.timeMs <= surroundedBridgeMs

                val useRect = nearest != null && (
                    kotlin.math.abs(nearest.timeMs - timeMs) <= nearestFillMs || surroundedMiss
                )

                if (useRect) {
                    result.add(
                        MaskKeyframe(
                            timeMs = timeMs,
                            rect = RectF(nearest!!.rect),
                            active = true
                        )
                    )
                } else {
                    result.add(
                        MaskKeyframe(
                            timeMs = timeMs,
                            rect = RectF(),
                            active = false
                        )
                    )
                }
            }
'''
s = replace_once(s, old, new, "OCR short-gap coverage")
vp.write_text(s, encoding="utf-8")


# 2) FAST clone-fill: bridge short tracker holes and choose donor from clean outside context.
sp = root / "app/src/main/java/com/akula/watermarkremover/SeamlessCloneProcessor.kt"
s = sp.read_text(encoding="utf-8")

if "val state = repairState(track, mid)" not in s:
    s = replace_once(
        s,
        "            val state = nearest(track, mid)",
        "            val state = repairState(track, mid)",
        "FAST repaired tracker state",
    )

marker = "    private fun nearest(sorted: List<MaskKeyframe>, timeMs: Long): MaskKeyframe {"
if "private fun repairState(" not in s:
    helper = '''    /** Bridge only a short inactive hole surrounded by the same-sized track. */
    private fun repairState(sorted: List<MaskKeyframe>, timeMs: Long): MaskKeyframe {
        val direct = nearest(sorted, timeMs)
        if (direct.active) return direct

        val previous = sorted.lastOrNull { it.timeMs <= timeMs && it.active }
        val next = sorted.firstOrNull { it.timeMs >= timeMs && it.active }
        if (previous == null || next == null) return direct
        if (next.timeMs - previous.timeMs > 1350L) return direct

        val pw = previous.rect.width().coerceAtLeast(1f)
        val ph = previous.rect.height().coerceAtLeast(1f)
        val nw = next.rect.width().coerceAtLeast(1f)
        val nh = next.rect.height().coerceAtLeast(1f)
        val sizeRatio = max(max(pw / nw, nw / pw), max(ph / nh, nh / ph))
        if (sizeRatio > 2.0f) return direct

        val chosen = if (
            kotlin.math.abs(previous.timeMs - timeMs) <=
            kotlin.math.abs(next.timeMs - timeMs)
        ) previous else next

        return MaskKeyframe(
            timeMs = timeMs,
            rect = RectF(chosen.rect),
            active = true,
            trackId = chosen.trackId,
            confidence = chosen.confidence * 0.85f
        )
    }

'''
    pos = s.find(marker)
    if pos < 0:
        raise SystemExit("ERROR FAST helper marker not found")
    s = s[:pos] + helper + s[pos:]
    print("OK FAST short-gap repair helper")

old_pad = '''        val px = max(3f, rect.width() * 0.05f)
        val py = max(3f, rect.height() * 0.09f)'''
new_pad = '''        val px = max(5f, rect.width() * 0.09f)
        val py = max(5f, rect.height() * 0.15f)'''
s = replace_once(s, old_pad, new_pad, "FAST mask halo padding")

start = s.find("    private fun borderScore(bitmap: Bitmap, dst: RectF, src: RectF): Double {")
end = s.find("    private fun fallbackSource(", start)
if start < 0 or end < 0:
    raise SystemExit("ERROR FAST borderScore block not found")

if "Destination samples are taken just outside" not in s[start:end]:
    border = '''    private fun borderScore(bitmap: Bitmap, dst: RectF, src: RectF): Double {
        val w = min(dst.width().toInt(), src.width().toInt()).coerceAtLeast(2)
        val h = min(dst.height().toInt(), src.height().toInt()).coerceAtLeast(2)
        val samples = 28
        var total = 0.0
        var count = 0

        fun diff(x1: Int, y1: Int, x2: Int, y2: Int) {
            val p1 = bitmap.getPixel(
                x1.coerceIn(0, bitmap.width - 1),
                y1.coerceIn(0, bitmap.height - 1)
            )
            val p2 = bitmap.getPixel(
                x2.coerceIn(0, bitmap.width - 1),
                y2.coerceIn(0, bitmap.height - 1)
            )
            val dr = Color.red(p1) - Color.red(p2)
            val dg = Color.green(p1) - Color.green(p2)
            val db = Color.blue(p1) - Color.blue(p2)
            total += abs(dr).toDouble() + abs(dg).toDouble() + abs(db).toDouble()
            count++
        }

        // Destination samples are taken just outside the repair rectangle.
        // This compares the donor to clean surrounding background, not to the
        // semi-transparent watermark pixels that we are trying to remove.
        val outside = 4
        for (i in 0 until samples) {
            val f = i.toFloat() / (samples - 1).toFloat()

            val dstX = dst.left.toInt() + (f * (w - 1)).toInt()
            val srcX = src.left.toInt() + (f * (w - 1)).toInt()
            diff(dstX, dst.top.toInt() - outside, srcX, src.top.toInt())
            diff(dstX, dst.bottom.toInt() + outside - 1, srcX, src.bottom.toInt() - 1)

            val dstY = dst.top.toInt() + (f * (h - 1)).toInt()
            val srcY = src.top.toInt() + (f * (h - 1)).toInt()
            diff(dst.left.toInt() - outside, dstY, src.left.toInt(), srcY)
            diff(dst.right.toInt() + outside - 1, dstY, src.right.toInt() - 1, srcY)
        }

        return if (count == 0) Double.MAX_VALUE else total / count.toDouble()
    }

'''
    s = s[:start] + border + s[end:]
    print("OK FAST clean-context donor score")
else:
    print("SKIP FAST donor score: already applied")

sp.write_text(s, encoding="utf-8")


# 3) QUALITY LaMa: sparse periodic refinement over a FAST-cleaned base video.
np = root / "app/src/main/java/com/akula/watermarkremover/NeuralInpainter.kt"
s = np.read_text(encoding="utf-8")
loop_start = s.find("            var aiDone = 0\n            for (index in frameFiles.indices) {")
loop_end = s.find("            val fps = totalFrames * 1000.0 / durationMs.toDouble()", loop_start)

if "LaMa INT8 гибрид:" not in s:
    if loop_start < 0 or loop_end < 0:
        raise SystemExit("ERROR LaMa loop not found")
    new_loop = '''            // FAST has already removed the watermark on every frame.
            // LaMa is only a sparse quality refinement, not 600-900 AI calls.
            val aiStride = when {
                totalFrames >= 800 -> 15
                totalFrames >= 450 -> 10
                totalFrames >= 250 -> 7
                else -> 4
            }
            val expectedAi = ((activeFrames + aiStride - 1) / aiStride).coerceAtLeast(1)
            var aiDone = 0
            var activeOrdinal = 0
            var wasActive = false

            for (index in frameFiles.indices) {
                val sourceFile = frameFiles[index]
                val outputFile = File(outFramesDir, sourceFile.name)
                val mask = masks[index]
                val active = isActiveMask(mask)

                if (!active) {
                    sourceFile.copyTo(outputFile, overwrite = true)
                    activeOrdinal = 0
                    wasActive = false
                } else {
                    activeOrdinal++
                    val runAi = !wasActive || (activeOrdinal - 1) % aiStride == 0

                    if (!runAi) {
                        // Keep THIS already-cleaned current frame. No stale frame copy.
                        sourceFile.copyTo(outputFile, overwrite = true)
                    } else {
                        val sourceBitmap = BitmapFactory.decodeFile(sourceFile.absolutePath)
                            ?: throw IllegalStateException("Не удалось открыть активный кадр")

                        val resultBitmap = try {
                            inpaintCurrentFrame(
                                env = ortEnv,
                                session = session,
                                source = sourceBitmap,
                                rawMask = mask
                            )
                        } finally {
                            if (!sourceBitmap.isRecycled) sourceBitmap.recycle()
                        }

                        try {
                            outputFile.outputStream().use { output ->
                                if (!resultBitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                                    throw IllegalStateException("Не удалось сохранить LaMa-кадр")
                                }
                            }
                        } finally {
                            if (!resultBitmap.isRecycled) resultBitmap.recycle()
                        }
                        aiDone++
                    }
                    wasActive = true
                }

                val percent = 7 + ((index + 1) * 83 / totalFrames)
                val videoPercent = ((index + 1) * 100 / totalFrames).coerceIn(0, 100)
                callback.onProgress(
                    percent.coerceIn(7, 90),
                    "LaMa INT8 гибрид: $aiDone/~$expectedAi AI • видео $videoPercent%"
                )
            }

'''
    s = s[:loop_start] + new_loop + s[loop_end:]
    print("OK sparse LaMa refinement")
else:
    print("SKIP sparse LaMa: already applied")
np.write_text(s, encoding="utf-8")


# 4) QUALITY UI pipeline: FAST first, sparse LaMa second.
ep = root / "app/src/main/java/com/akula/watermarkremover/EditorActivity.kt"
s = ep.read_text(encoding="utf-8")
qstart = s.find("        if (binding.radioQuality.isChecked) {")
qend = s.find("        } else {\n            VideoProcessor.removeWatermarkAndExport(", qstart)

if "Качество 1/2: очистка FAST" not in s:
    if qstart < 0 or qend < 0:
        raise SystemExit("ERROR quality branch not found")
    quality = '''        if (binding.radioQuality.isChecked) {
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
'''
    s = s[:qstart] + quality + s[qend:]
    print("OK hybrid quality UI")
else:
    print("SKIP hybrid quality UI: already applied")
ep.write_text(s, encoding="utf-8")

print("DONE: FAST coverage + donor matching + sparse LaMa hybrid")
