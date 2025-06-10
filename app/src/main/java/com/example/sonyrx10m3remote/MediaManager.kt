package com.example.sonyrx10m3remote

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.*
import android.widget.ImageView
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

class MediaManager(
    private val context: Context,
    private val cameraController: CameraController,
    private val imageViewLivePreview: ImageView,
    private val resumeLiveViewCallback: () -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val TAG = "MediaManager"

    suspend fun onPhotoCaptured(mediaInfo: MediaInfo) {
        val tempFile = downloadPreview(mediaInfo.previewUrl)
        tempFile?.let {
            displayPreviewImageTemporarily(it)
        }

        if (prefs.getBoolean("auto_download_jpeg", false)) {
            downloadImage(mediaInfo.fullImageUrl, mediaInfo.filename)
        }
    }

    // ----- Preview Image Decoding Helpers -----
    fun loadPreviewBitmap(imageFile: File, targetWidth: Int, targetHeight: Int): Bitmap {
        // Step 1: Downscale bitmap
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(imageFile.absolutePath, options)

        options.inSampleSize = calculateInSampleSize(options, targetWidth, targetHeight)
        options.inJustDecodeBounds = false

        val rawBitmap = BitmapFactory.decodeFile(imageFile.absolutePath, options)
        return rotateImageIfRequired(rawBitmap, imageFile)
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height, width) = options.outHeight to options.outWidth
        var inSampleSize = 1

        while ((height / inSampleSize) >= reqHeight && (width / inSampleSize) >= reqWidth) {
            inSampleSize *= 2
        }
        return inSampleSize
    }

    private fun rotateImageIfRequired(bitmap: Bitmap, imageFile: File): Bitmap {
        val exif = ExifInterface(imageFile.absolutePath)
        val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            else -> return bitmap
        }

        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
    // ----------

    private suspend fun downloadPreview(url: String): File? = withContext(Dispatchers.IO) {
        return@withContext try {
            val tmpFile = File.createTempFile("preview_", ".jpg", context.cacheDir)
            URL(url).openStream().use { input ->
                tmpFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            tmpFile
        } catch (e: Exception) {
            Log.e(TAG, "Preview download failed: ${e.localizedMessage}")
            null
        }
    }

    private fun displayPreviewImageTemporarily(imageFile: File) {
        val targetWidth = imageViewLivePreview.width
        val targetHeight = imageViewLivePreview.height

        if (targetWidth == 0 || targetHeight == 0) {
            imageViewLivePreview.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    imageViewLivePreview.viewTreeObserver.removeOnPreDrawListener(this)
                    displayPreviewImageTemporarily(imageFile) // retry now that dimensions are valid
                    return true
                }
            })
            return
        }

        // now safe to decode and display the bitmap using targetWidth and targetHeight
        val bmp = loadPreviewBitmap(imageFile, targetWidth, targetHeight)

        handler.post {
            imageViewLivePreview.setImageBitmap(bmp)
        }

        handler.postDelayed({
            resumeLiveViewCallback()
        }, 1000)
    }

    data class MediaInfo(
        val previewUrl: String,
        val fullImageUrl: String,
        val filename: String,
        val isVideo: Boolean = false
    )

    // Function for media manager support - downloads an image from the camera
    suspend fun downloadImage(url: String, filename: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val mimeType = "image/jpeg"
            val contentValues = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/SonyRX10M3Remote")
                put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
            }

            val resolver = context.contentResolver
            val collection = android.provider.MediaStore.Images.Media.getContentUri("external")

            val imageUri = resolver.insert(collection, contentValues)
            if (imageUri == null) {
                Log.e(TAG, "Failed to create MediaStore entry.")
                return@withContext false
            }

            resolver.openOutputStream(imageUri).use { outputStream ->
                if (outputStream == null) {
                    Log.e(TAG, "Failed to get output stream.")
                    return@withContext false
                }

                URL(url).openStream().use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            }

            // Mark file as no longer pending
            contentValues.clear()
            contentValues.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(imageUri, contentValues, null, null)

            Log.d(TAG, "Image saved to MediaStore: $imageUri")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            false
        }
    }
}