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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL

class MediaManager(
    private val context: Context,
    private val cameraController: CameraController,
    private val imageViewLivePreview: ImageView,
    private val resumeLiveViewCallback: () -> Unit,
    private val cameraId: String?
) {
    private val handler = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val TAG = "MediaManager"

    private val mediaStoreHelper = MediaStoreHelper(context)
    private val cacheManager = CacheManager(context)

    // Variables for live image cache (metadata)
    private val _seenUris = mutableSetOf<String>()
    private val _cachedImages = mutableListOf<ContentItem>()
    private val _cachedVideos = mutableListOf<ContentItem>()
    val seenUris: MutableSet<String> = mutableSetOf()
    val cachedImages: List<ContentItem> get() = _cachedImages
    val cachedVideos: List<ContentItem> get() = _cachedVideos

    // Variables for live image cache (thumbnail)
    private val _thumbnailCache = MutableStateFlow<Map<String, Uri>>(emptyMap())
    val thumbnailCache: StateFlow<Map<String, Uri>> get() = _thumbnailCache

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
        val localThumbUri = if (cameraId != null && item.thumbnailUrl != null) {
            val file = cacheManager.getThumbnailFile(cameraId, item.uri)
            if (file.exists() && file.length() > 0) Uri.fromFile(file) else null
        } else null

        return CapturedImage(
            uri = item.uri,
            remoteUrl = item.remoteUrl,
            thumbnailUrl = item.thumbnailUrl,
            fileName = item.fileName,
            timestamp = item.timestamp,
            downloaded = false,
            contentKind = item.contentKind,
            lastModified = item.lastModified,
            localUri = localThumbUri
        )
    }

    // ---------------- Live Image Cache ----------------

    fun addToCache(images: List<ContentItem>, videos: List<ContentItem>) {
        val imageMap = _cachedImages.associateBy { it.uri }.toMutableMap()
        for (item in images) {
            imageMap[item.uri] = item // Overwrites existing entries with updated ones
        }
        _cachedImages.clear()
        _cachedImages.addAll(imageMap.values)

        val videoMap = _cachedVideos.associateBy { it.uri }.toMutableMap()
        for (item in videos) {
            videoMap[item.uri] = item
        }
        _cachedVideos.clear()
        _cachedVideos.addAll(videoMap.values)
    }

    fun clearCache() {
        _seenUris.clear()
        _cachedImages.clear()
        _cachedVideos.clear()
    }

    /**
     * Downloads missing thumbnails for the given images and updates the thumbnail cache.
     * Calls [onDownloaded] with each updated CapturedImage if provided.
     */
    suspend fun downloadMissingThumbnails(
        cameraId: String?,
        images: List<CapturedImage>,
        onDownloaded: (CapturedImage) -> Unit
    ) {
        if (cameraId == null) return

        for (image in images) {
            // Skip if localUri exists or thumbnailUrl missing or already cached
            if (image.localUri != null || image.thumbnailUrl == null || isThumbnailCached(image.uri)) continue

            val localUri = cacheManager.downloadAndSaveThumbnail(
                cameraId = cameraId,
                imageUri = image.uri,
                thumbnailUrl = image.thumbnailUrl
            )

            if (localUri != null) {
                // Update internal cache map
                _thumbnailCache.update { currentMap ->
                    currentMap + (image.uri to localUri)
                }

                val updated = image.copy(localUri = localUri)

                withContext(Dispatchers.Main) {
                    onDownloaded(updated)
                }
            }
        }
    }

    // ---------------- Disk Cache ----------------

    fun isThumbnailCached(uri: String): Boolean {
        return _thumbnailCache.value.containsKey(uri)
    }

    /**
     * Load from disk cache into live cache.
     * Clears existing live cache first, then populates from disk.
     * Must be called from a coroutine/suspend context.
     */
    suspend fun loadDiskCacheIntoLive(cameraId: String) {
        val cachedCapturedItems = cacheManager.loadCachedMetadata(cameraId)
        Log.d(TAG, "Loaded ${cachedCapturedItems.size} items from disk cache for camera $cameraId")

        clearCache()

        // Build thumbnail cache map
        val thumbnailMap = mutableMapOf<String, Uri>()

        cachedCapturedItems
            .distinctBy { it.uri }
            .forEach { captured ->
                val item = capturedToContentItem(captured)

                // Insert into thumbnail map if local URI exists
                captured.localUri?.let { thumbnailMap[captured.uri] = it }

                when (item.contentKind?.lowercase()) {
                    "still", "image" -> _cachedImages.add(item)
                    "video", "movie" -> _cachedVideos.add(item)
                    else -> _cachedImages.add(item)
                }

                _seenUris.add(item.uri)
            }

        // Push map to StateFlow
        _thumbnailCache.value = thumbnailMap
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

