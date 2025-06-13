package com.example.sonyrx10m3remote.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewTreeObserver
import android.widget.ImageView
import androidx.exifinterface.media.ExifInterface
import com.example.sonyrx10m3remote.camera.CameraController
import com.example.sonyrx10m3remote.camera.CameraController.ContentItem
import com.example.sonyrx10m3remote.data.CacheManager
import com.example.sonyrx10m3remote.data.CapturedImage
import com.example.sonyrx10m3remote.gallery.SessionImageRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL

class MediaManager(
    private val context: Context,
    private val cameraController: CameraController,
    private val imageViewLivePreview: ImageView,
    private val resumeLiveViewCallback: () -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val TAG = "MediaManager"

    private val mediaStoreHelper = MediaStoreHelper(context)
    private val cacheManager = CacheManager(context)

    // Variable for live image cache
    private val _seenUris = mutableSetOf<String>()
    private val _cachedImages = mutableListOf<ContentItem>()
    private val _cachedVideos = mutableListOf<ContentItem>()
    val seenUris: MutableSet<String> = mutableSetOf()
    val cachedImages: List<ContentItem> get() = _cachedImages
    val cachedVideos: List<ContentItem> get() = _cachedVideos

    // ---------------- Object Converters ----------------

    // Used for converting persistent metadata into ContentItem
    fun capturedToContentItem(item: CapturedImage): ContentItem {
        return ContentItem(
            uri = item.uri,
            remoteUrl = item.remoteUrl ?: "",
            thumbnailUrl = item.thumbnailUrl ?: "",
            fileName = item.fileName ?: "",
            timestamp = item.timestamp,
            contentKind = item.contentKind ?: "image", // or "unknown"
            lastModified = item.lastModified
        )
    }

    // Used for converting ContentItem to CapturedImage for internal app usage
    fun contentToCaptured(item: ContentItem): CapturedImage {
        return CapturedImage(
            uri = item.uri,
            remoteUrl = item.remoteUrl,
            thumbnailUrl = item.thumbnailUrl,
            fileName = item.fileName,
            timestamp = item.timestamp,
            contentKind = item.contentKind,
            lastModified = item.lastModified
        )
    }

    // ---------------- Live Image Cache ----------------

    fun addToCache(images: List<ContentItem>, videos: List<ContentItem>) {
        // Filter out images with duplicate URIs
        val existingImageUris = _cachedImages.map { it.uri }.toSet()
        val newUniqueImages = images.filter { it.uri !in existingImageUris }
        _cachedImages.addAll(newUniqueImages)

        // Similarly for videos
        val existingVideoUris = _cachedVideos.map { it.uri }.toSet()
        val newUniqueVideos = videos.filter { it.uri !in existingVideoUris }
        _cachedVideos.addAll(newUniqueVideos)
    }

    fun clearCache() {
        _seenUris.clear()
        _cachedImages.clear()
        _cachedVideos.clear()
    }

    // ---------------- Disk Cache ----------------

    /**
     * Load from disk cache into live cache.
     * Clears existing live cache first, then populates from disk.
     * Must be called from a coroutine/suspend context.
     */
    suspend fun loadDiskCacheIntoLive(cameraId: String) {
        // Load list of CapturedImage from disk via CacheManager
        val cachedCapturedItems = cacheManager.loadCachedMetadata(cameraId)
        Log.d(TAG, "Loaded ${cachedCapturedItems.size} items from disk cache for camera $cameraId")

        // Clear current live cache
        clearCache()

        // Populate live cache
        cachedCapturedItems
            .distinctBy { it.uri }
            .forEach { captured ->
                val item = capturedToContentItem(captured)
                when (item.contentKind?.lowercase()) {
                    "still", "image" -> _cachedImages.add(item)
                    "video", "movie" -> _cachedVideos.add(item)
                    else -> _cachedImages.add(item)
                }
                _seenUris.add(item.uri)
            }
    }

    /**
     * Persist current live cache to disk.
     * Must be called from a coroutine/suspend context.
     */
    suspend fun saveLiveCacheToDisk(cameraId: String) {
        // Convert all live cache items to CapturedImage
        val allItems = (_cachedImages + _cachedVideos).map { contentToCaptured(it) }
        cacheManager.saveCachedMetadata(cameraId, allItems)
        Log.d(TAG, "Saved ${allItems.size} live cache items to disk for camera $cameraId")
    }

    /**
     * Clear disk cache for this cameraId.
     * Must be called from coroutine/suspend context or wrapped in withContext(Dispatchers.IO).
     */
    suspend fun clearDiskCache(cameraId: String) {
        // Option A: delete entire folder
        cacheManager.clearCache(cameraId)
        Log.d(TAG, "Cleared disk cache for camera $cameraId")
    }

    // ---------------- Session Media Handler ----------------

    // Listener interface for new session images
    interface SessionImageListener {
        fun onNewSessionImage(image: CapturedImage)
    }

    // Nullable listener property
    var sessionImageListener: SessionImageListener? = null

    suspend fun onPhotoCaptured(mediaInfo: MediaInfo) {
        val tempFile = downloadPreview(mediaInfo.previewUrl)
        tempFile?.let {
            displayPreviewImageTemporarily(it)

            // Notify listener with a new CapturedImage for session gallery
            val sessionImage = CapturedImage(
                uri = mediaInfo.filename,
                remoteUrl = mediaInfo.fullImageUrl,
                lastModified = System.currentTimeMillis()
            )
            sessionImageListener?.onNewSessionImage(sessionImage)
        }

        if (prefs.getBoolean("auto_download_jpeg", false)) {
            val uri = mediaStoreHelper.downloadImage(mediaInfo.fullImageUrl, mediaInfo.filename)
            if (uri != null) {
                Log.d(
                    "MediaManager",
                    "Downloaded image, updating SessionImageRepository with URI: $uri"
                )
                SessionImageRepository.updateImageUri(mediaInfo.filename, uri)
            }
        }
    }

    // ---------------- Preview Image Decoding Helpers ----------------

    fun loadPreviewBitmap(imageFile: File, targetWidth: Int, targetHeight: Int): Bitmap {
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
    // ---------------- Downloading and Displaying Previews ----------------

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
                    displayPreviewImageTemporarily(imageFile)
                    return true
                }
            })
            return
        }

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
}

