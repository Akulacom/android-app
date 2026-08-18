package com.akula.watermarkremover

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.akula.watermarkremover.databinding.ActivityPhotoEditorBinding
import java.io.File
import java.io.FileOutputStream

class PhotoEditorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_IMAGE_URI = "extra_image_uri"
    }

    private lateinit var binding: ActivityPhotoEditorBinding
    private lateinit var imageUri: Uri
    private lateinit var sourceBitmap: Bitmap

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPhotoEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val uriString = intent.getStringExtra(EXTRA_IMAGE_URI)
            ?: run { finish(); return }
        imageUri = Uri.parse(uriString)

        try {
            sourceBitmap = decodeBitmap(imageUri)
            binding.imageView.setImageBitmap(sourceBitmap)
        } catch (e: Exception) {
            Toast.makeText(this, "Не удалось открыть фото: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        binding.btnReset.setOnClickListener {
            binding.maskOverlay.maskRect.setEmpty()
            binding.maskOverlay.invalidate()
            binding.tvStatus.text = "Обведи watermark рамкой"
        }

        binding.btnRemove.setOnClickListener {
            startLaMaRemoval()
        }
    }

    private fun startLaMaRemoval() {
        if (!binding.maskOverlay.hasMask()) {
            Toast.makeText(this, "Сначала обведи watermark рамкой", Toast.LENGTH_SHORT).show()
            return
        }

        val mask = currentMaskInBitmapCoords()
        if (mask == null || mask.width() < 2f || mask.height() < 2f) {
            Toast.makeText(this, "Не удалось определить область watermark", Toast.LENGTH_SHORT).show()
            return
        }

        binding.btnRemove.isEnabled = false
        binding.btnReset.isEnabled = false
        binding.progressBar.visibility = View.VISIBLE
        binding.progressBar.progress = 0
        binding.tvStatus.text = "Подготовка LaMa..."

        NeuralInpainter.processPhoto(
            context = this,
            source = sourceBitmap,
            maskRect = mask,
            callback = object : NeuralInpainter.PhotoCallback {
                override fun onProgress(percent: Int, stage: String) {
                    runOnUiThread {
                        binding.progressBar.progress = percent.coerceIn(0, 100)
                        binding.tvStatus.text = stage
                    }
                }

                override fun onSuccess(bitmap: Bitmap) {
                    runOnUiThread {
                        val old = sourceBitmap
                        sourceBitmap = bitmap
                        binding.imageView.setImageBitmap(sourceBitmap)
                        binding.maskOverlay.maskRect.setEmpty()
                        binding.maskOverlay.invalidate()
                        binding.progressBar.progress = 100

                        try {
                            val saved = savePhoto(sourceBitmap)
                            binding.tvStatus.text = "Готово. Сохранено: $saved"
                            Toast.makeText(
                                this@PhotoEditorActivity,
                                "Watermark удалён и фото сохранено",
                                Toast.LENGTH_LONG
                            ).show()
                        } catch (e: Exception) {
                            binding.tvStatus.text = "LaMa готово, но ошибка сохранения: ${e.message}"
                        }

                        binding.btnRemove.isEnabled = true
                        binding.btnReset.isEnabled = true
                        if (old !== bitmap && !old.isRecycled) old.recycle()
                    }
                }

                override fun onError(message: String) {
                    runOnUiThread {
                        binding.progressBar.visibility = View.GONE
                        binding.tvStatus.text = "Ошибка: $message"
                        binding.btnRemove.isEnabled = true
                        binding.btnReset.isEnabled = true
                        Toast.makeText(this@PhotoEditorActivity, message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        )
    }

    private fun currentMaskInBitmapCoords(): RectF? {
        if (!binding.maskOverlay.hasMask()) return null

        val viewW = binding.imageView.width.toFloat()
        val viewH = binding.imageView.height.toFloat()
        if (viewW <= 0f || viewH <= 0f) return null

        val bitmapW = sourceBitmap.width.toFloat()
        val bitmapH = sourceBitmap.height.toFloat()
        val bitmapAspect = bitmapW / bitmapH
        val viewAspect = viewW / viewH

        val contentW: Float
        val contentH: Float
        val contentLeft: Float
        val contentTop: Float

        if (viewAspect > bitmapAspect) {
            contentH = viewH
            contentW = viewH * bitmapAspect
            contentLeft = (viewW - contentW) / 2f
            contentTop = 0f
        } else {
            contentW = viewW
            contentH = viewW / bitmapAspect
            contentLeft = 0f
            contentTop = (viewH - contentH) / 2f
        }

        val r = binding.maskOverlay.maskRect
        val contentRight = contentLeft + contentW
        val contentBottom = contentTop + contentH

        val left = r.left.coerceIn(contentLeft, contentRight - 2f)
        val top = r.top.coerceIn(contentTop, contentBottom - 2f)
        val right = r.right.coerceIn(left + 2f, contentRight)
        val bottom = r.bottom.coerceIn(top + 2f, contentBottom)

        val scaleX = bitmapW / contentW
        val scaleY = bitmapH / contentH

        return RectF(
            ((left - contentLeft) * scaleX).coerceIn(0f, bitmapW - 2f),
            ((top - contentTop) * scaleY).coerceIn(0f, bitmapH - 2f),
            ((right - contentLeft) * scaleX).coerceIn(2f, bitmapW),
            ((bottom - contentTop) * scaleY).coerceIn(2f, bitmapH)
        )
    }

    private fun decodeBitmap(uri: Uri): Bitmap {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = true

                val w = info.size.width
                val h = info.size.height
                val maxSide = maxOf(w, h)
                if (maxSide > 4096) {
                    val scale = 4096f / maxSide.toFloat()
                    decoder.setTargetSize(
                        (w * scale).toInt().coerceAtLeast(1),
                        (h * scale).toInt().coerceAtLeast(1)
                    )
                }
            }
        } else {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalStateException("пустой файл")
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: throw IllegalStateException("неподдерживаемое изображение")
        }
    }

    private fun savePhoto(bitmap: Bitmap): String {
        val fileName = "watermark_removed_${System.currentTimeMillis()}.jpg"

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/WatermarkRemover"
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }

            val uri = contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                values
            ) ?: throw IllegalStateException("не удалось создать файл")

            contentResolver.openOutputStream(uri)?.use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 96, out)) {
                    throw IllegalStateException("ошибка JPEG")
                }
            } ?: throw IllegalStateException("не удалось открыть файл")

            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            fileName
        } else {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                "WatermarkRemover"
            ).apply { mkdirs() }
            val file = File(dir, fileName)
            FileOutputStream(file).use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 96, out)) {
                    throw IllegalStateException("ошибка JPEG")
                }
            }
            file.absolutePath
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::sourceBitmap.isInitialized && !sourceBitmap.isRecycled) {
            sourceBitmap.recycle()
        }
    }
}
