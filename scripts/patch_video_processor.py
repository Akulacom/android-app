from pathlib import Path

path = Path('app/src/main/java/com/akula/watermarkremover/VideoProcessor.kt')
text = path.read_text(encoding='utf-8')

anchor = '''                val videoSize = readVideoSize(inputPath)
                if (videoSize == null) {
                    callback.onError("Не удалось определить размер видео")
                    return@Thread
                }
'''

insertion = anchor + '''

                // Основной быстрый путь без размытого removelogo-пятна.
                // Берём похожий фон из соседней области того же текущего кадра,
                // поэтому текстура продолжает двигаться вместе с камерой.
                SeamlessCloneProcessor.process(
                    inputPath = inputPath,
                    outputPath = outputPath,
                    keyframes = processingKeyframes,
                    durationMs = durationMs,
                    videoWidth = videoSize.first,
                    videoHeight = videoSize.second,
                    callback = object : SeamlessCloneProcessor.Callback {
                        override fun onProgress(percent: Int) {
                            callback.onProgress(percent)
                        }

                        override fun onSuccess(outputPath: String) {
                            callback.onSuccess(outputPath)
                        }

                        override fun onError(message: String) {
                            callback.onError(message)
                        }
                    }
                )
                return@Thread
'''

if 'SeamlessCloneProcessor.process(' in text:
    print('Seamless clone already active')
else:
    if anchor not in text:
        raise SystemExit('VideoProcessor anchor not found')
    text = text.replace(anchor, insertion, 1)
    path.write_text(text, encoding='utf-8')
    print('Seamless clone activated')
