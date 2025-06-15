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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import java.time.*
import kotlin.collections.joinToString

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
    val seenUris: MutableSet<String> = mutableSetOf()

    // Variables for live image cache (thumbnail)
    private val _thumbnailCache = MutableStateFlow<Map<String, Uri>>(emptyMap())
    val thumbnailCache: StateFlow<Map<String, Uri>> get() = _thumbnailCache
    private val pendingThumbnailDownloads = mutableSetOf<String>()

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

    // ---------------- Thumbnail Management ----------------

    fun isThumbnailCached(uri: String): Boolean {
        return _thumbnailCache.value.containsKey(uri)
    }

    /**
     * Downloads missing thumbnails for the given images and updates the thumbnail cache.
     * Calls [onDownloaded] with each updated CapturedImage if provided.
     */
    // Function to queue a thumbnail download for a requested image
    fun requestThumbnailDownload(cameraId: String, image: CapturedImage, onDownloaded: (CapturedImage) -> Unit) {
        // If already downloaded or in progress, skip
        if (image.localUri != null || image.thumbnailUrl == null || pendingThumbnailDownloads.contains(image.uri)) return

        pendingThumbnailDownloads.add(image.uri)

        CoroutineScope(Dispatchers.IO).launch {
            val localUri = cacheManager.downloadAndSaveThumbnail(cameraId, image.uri, image.thumbnailUrl)
            if (localUri != null) {
                _thumbnailCache.update { currentMap -> currentMap + (image.uri to localUri) }
                val updatedImage = image.copy(localUri = localUri)
                withContext(Dispatchers.Main) {
                    onDownloaded(updatedImage)
                }
            }
            pendingThumbnailDownloads.remove(image.uri)
        }
    }

    // ---------------- Live Cache ----------------

    // For full disk cache refresh
    var cachedImagesByDate: MutableMap<String, MutableList<CapturedImage>> = mutableMapOf()
    var cachedVideosByDate: MutableMap<String, MutableList<CapturedImage>> = mutableMapOf()

    fun clearCache(cameraId: String) {
        try {
            // Clear the thumbnailCache StateFlow by emitting an empty map
            _thumbnailCache.value = emptyMap()

            // Clear seenUris set as usual
            _seenUris.clear()

            // Clear disk cache files
            val success = cacheManager.clearCache(cameraId)

            Log.d("MediaManager", "Cache cleared for camera $cameraId: $success")

        } catch (e: Exception) {
            Log.e("MediaManager", "Error clearing cache: ${e.localizedMessage}")
        }
    }

    // ---------------- Disk Cache ----------------

    suspend fun getCachedDates(cameraId: String): List<String> {
        return cacheManager.loadKnownDates(cameraId)
    }

    suspend fun loadMetadataForDate(cameraId: String, dateKey: String): List<ContentItem> {
        val capturedImages = cacheManager.loadCachedMetadataForDate(cameraId, dateKey)
        val result = mutableListOf<ContentItem>()

        for (captured in capturedImages) {
            val localUri = captured.localUri
            val valid = localUri?.let { isLocalFileValid(it) } ?: false
            if (localUri != null && !valid) continue

            val content = capturedToContentItem(captured)

            if (valid) {
                _thumbnailCache.update { it + (captured.uri to localUri!!) }
            }

            _seenUris.add(content.uri)
            result.add(content)
        }

        return result
    }

    suspend fun loadPreviewImagesForDate(
        cameraId: String,
        dateKey: String,
        count: Int = 4
    ): List<CapturedImage> {
        return cacheManager.loadCachedMetadataForDate(cameraId, dateKey).take(count)
    }

    /**
     * Save the current live cache to disk, grouped by date.
     * @param cameraId The camera ID.
     * @param groupedCapturedImages Map of dateKey -> List<CapturedImage>
     */
    suspend fun saveCacheToDisk(
        cameraId: String,
        cachedDates: List<String>,
        contentForDate: Map<String, List<CapturedImage>>,
        selectedDate: String?
    ) {
        try {
            // Always save the known date index to disk
            cacheManager.saveKnownDates(cameraId, cachedDates)

            // Only update metadata for selectedDate if we have it
            if (selectedDate != null && contentForDate.containsKey(selectedDate)) {
                val capturedImages = contentForDate[selectedDate] ?: emptyList()
                cacheManager.saveCachedMetadataForDate(cameraId, selectedDate, capturedImages)
                Log.d("MediaManager", "Saved metadata for selected date: $selectedDate")
            }

            Log.d("MediaManager", "Partial cache save complete for camera $cameraId")

        } catch (e: Exception) {
            Log.e("MediaManager", "Error saving cache to disk: ${e.localizedMessage}")
        }
    }

    /**
     * Save the entire cache to disk.
     */
    suspend fun saveCacheToDiskFull(
        cameraId: String,
        cachedDates: List<String>,
        contentForDate: Map<String, List<CapturedImage>>
    ) {
        try {
            cacheManager.saveKnownDates(cameraId, cachedDates)

            for (dateKey in cachedDates) {
                val capturedImages = contentForDate[dateKey] ?: emptyList()
                cacheManager.saveCachedMetadataForDate(cameraId, dateKey, capturedImages)
                Log.d("MediaManager", "Saved metadata for date: $dateKey")
            }

            Log.d("MediaManager", "Full cache save complete for camera $cameraId")
        } catch (e: Exception) {
            Log.e("MediaManager", "Error saving full cache to disk: ${e.localizedMessage}")
        }
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

    // Helper function to check if a localUri points to a valid (uncorrupted/missing) file
    fun isLocalFileValid(uri: Uri): Boolean {
        val file = File(uri.path ?: return false)
        return file.exists() && file.length() > 0
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
                uri = mediaInfo.fileName,
                remoteUrl = mediaInfo.fullImageUrl,
                lastModified = System.currentTimeMillis()
            )
            sessionImageListener?.onNewSessionImage(sessionImage)
        }

        if (prefs.getBoolean("auto_download_jpeg", false)) {
            val uri = mediaStoreHelper.downloadImage(mediaInfo.fullImageUrl, mediaInfo.fileName)
            if (uri != null) {
                Log.d(
                    "MediaManager",
                    "Downloaded image, updating SessionImageRepository with URI: $uri"
                )
                SessionImageRepository.updateImageUri(mediaInfo.fileName, uri)
            }
        }
    }

    suspend fun processBatchDownload(mediaInfo: MediaInfo) {
        // Only do the download & session update logic — skip live preview display
        if (prefs.getBoolean("auto_download_jpeg", false)) {
            val uri = mediaStoreHelper.downloadImage(mediaInfo.fullImageUrl, mediaInfo.fileName)
            if (uri != null) {
                Log.d(
                    "MediaManager",
                    "Batch downloaded image, updating SessionImageRepository with URI: $uri"
                )
                SessionImageRepository.updateImageUri(mediaInfo.fileName, uri)
            }
        }
    }

    private fun extractFileName(url: String): String {
        return url.substringAfterLast('/')
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
        val fileName: String,
        val isVideo: Boolean = false
    )
}

