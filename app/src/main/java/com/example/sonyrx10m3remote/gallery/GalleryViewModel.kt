package com.example.sonyrx10m3remote.gallery

import android.content.Context
import android.util.Log
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.sonyrx10m3remote.media.MediaManager
import com.example.sonyrx10m3remote.media.MediaManager.MediaInfo
import com.example.sonyrx10m3remote.media.MediaStoreHelper
import com.example.sonyrx10m3remote.camera.CameraController
import com.example.sonyrx10m3remote.camera.CameraController.ContentItem
import com.example.sonyrx10m3remote.data.CapturedImage
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

enum class GalleryMode {
    SESSION,
    CAMERA_SD,
    DOWNLOADED
}

enum class CameraSdViewType {
    IMAGES,
    VIDEOS
}

enum class GroupingMode {
    FLAT,
    BY_DATE
}

class GalleryViewModel(
    private val context: Context,
    private val mediaManager: MediaManager?,
    private val cameraController: CameraController?,
    private val cameraId: String?
) : ViewModel() {
    private val mediaStoreHelper = MediaStoreHelper(context)

    companion object {
        private const val TAG = "GalleryViewModel"
    }

    // StateFlows holding images from Session, Camera SD and Downloaded modes
    private val _sessionImages = MutableStateFlow<List<CapturedImage>>(emptyList())
    val sessionImages: StateFlow<List<CapturedImage>> = _sessionImages
    private val _downloadedImages = MutableStateFlow<List<CapturedImage>>(emptyList())
    val downloadedImages: StateFlow<List<CapturedImage>> = _downloadedImages

    // Date-based cache state
    private val _cachedDates = MutableStateFlow<List<String>>(emptyList()) // list of date keys (e.g. "20250614")
    val cachedDates: StateFlow<List<String>> = _cachedDates
    private val _datePreviews = MutableStateFlow<Map<String, List<CapturedImage>>>(emptyMap())
    val datePreviews: StateFlow<Map<String, List<CapturedImage>>> = _datePreviews
    private val _contentForDate = MutableStateFlow<List<CapturedImage>>(emptyList())
    val contentForDate: StateFlow<List<CapturedImage>> = _contentForDate
    private val _selectedDate = MutableStateFlow<String?>(null)
    val selectedDate: StateFlow<String?> = _selectedDate
    private val _loadingDates = MutableStateFlow<Set<String>>(emptySet())
    val loadingDates: StateFlow<Set<String>> = _loadingDates
    private val _loadedDates = mutableSetOf<String>()

    // StateFlow holding the current view type (image or video)
    private val _cameraSdViewType = MutableStateFlow(CameraSdViewType.IMAGES)
    val cameraSdViewType: StateFlow<CameraSdViewType> = _cameraSdViewType

    // StateFlows holding the loading status of processes
    private val _sessionLoading = MutableStateFlow(true)
    val sessionLoading: StateFlow<Boolean> = _sessionLoading
    private val _cameraSdLoading = MutableStateFlow(false)
    val cameraSdLoading: StateFlow<Boolean> = _cameraSdLoading
    private val _downloadedLoading = MutableStateFlow(true)
    val downloadedLoading: StateFlow<Boolean> = _downloadedLoading
    private val _isSelectedDateLoading = MutableStateFlow(false)
    val isSelectedDateLoading: StateFlow<Boolean> = _isSelectedDateLoading

    // StateFlows holding images collected by an intervalometer job to display for choosing
    private val _capturedImages = MutableStateFlow<List<CapturedImage>>(emptyList())
    val capturedImages: StateFlow<List<CapturedImage>> = _capturedImages    // Tracks selected images for download
    private val _chosenImages = MutableStateFlow<Set<CapturedImage>>(emptySet())
    val chosenImages: StateFlow<Set<CapturedImage>> = _chosenImages
    private val _showIntervalPopup = MutableStateFlow(false)
    val showIntervalPopup: StateFlow<Boolean> = _showIntervalPopup.asStateFlow()
    private val _isDownloading = MutableStateFlow(false)
    val isDownloading: StateFlow<Boolean> = _isDownloading.asStateFlow()

    // StateFlow holding the image currently selected in the gallery
    private val _selectedImage = MutableStateFlow<CapturedImage?>(null)
    val selectedImage: StateFlow<CapturedImage?> = _selectedImage

    // StateFlow holding the current gallery mode (Session, Camera SD or Downloaded)
    private val _mode = MutableStateFlow(GalleryMode.SESSION)
    val mode: StateFlow<GalleryMode> = _mode
    private val _isInSelectionMode = MutableStateFlow(false)
    val isInSelectionMode: StateFlow<Boolean> = _isInSelectionMode

    init {
        _sessionLoading.value = true
        viewModelScope.launch {
            SessionImageRepository.sessionImages.collect { sessionList ->
                Log.d("GalleryViewModel", "Session images updated: ${sessionList.size} images")
                _sessionImages.value = sessionList
            }
        }
        _sessionLoading.value = false
    }

    // --------------- Gallery UI ------------------

    fun onGalleryOpened() {
        viewModelScope.launch {
            // Set camera mode to Contents Transfer
            if (cameraController != null) {
                try {
                    cameraController.waitForIdleStatus()
                    val success = cameraController.setCameraFunction("Contents Transfer")
                    if (!success) {
                        Log.e("GalleryViewModel", "Failed to set camera function to Contents Transfer")
                    }
                } catch (e: Exception) {
                    Log.e("GalleryViewModel", "Error setting camera function to Contents Transfer: ${e.localizedMessage}")
                }
            }
        }
    }

    fun onGalleryClosed() {
        if (cameraController == null) return
        viewModelScope.launch {
            try {
                withContext(NonCancellable) {
                    cameraController.waitForIdleStatus()
                    val success = cameraController.setCameraFunction("Remote Shooting")
                    if (!success) {
                        Log.e("GalleryViewModel", "Failed to set camera function to Remote Shooting")
                    }
                }
            } catch (e: Exception) {
                Log.e("GalleryViewModel", "Error setting camera function to Remote Shooting: ${e.localizedMessage}")
            }

            try {
                // Call the restartLiveView callback here to start MJPEG stream & UI stuff
                Log.d("GalleryViewModel", "Attempting to invoke restartLiveViewCallback...")
                SessionImageRepository.restartLiveViewCallback?.invoke() ?: Log.w("GalleryViewModel", "restartLiveViewCallback is null!")

            } catch (e: Exception) {
                Log.e("GalleryViewModel", "Error starting live view: ${e.localizedMessage}")
            }

            val success = cameraController.ensureContShootingModeSingle()
            if (!success) {
                Log.w("GalleryViewModel", "Failed to ensure continuous shooting mode is Single")
            }
        }
    }

    fun selectImage(image: CapturedImage?) {
        Log.d("GalleryViewModel", "selectImage called with $image")
        _selectedImage.value = image
    }

    fun enterSelectionMode() {
        _isInSelectionMode.value = true
    }

    fun exitSelectionMode() {
        _isInSelectionMode.value = false
        _chosenImages.value = emptySet()
    }

    fun setMode(newMode: GalleryMode) {
        _mode.value = newMode

        when (newMode) {
            GalleryMode.DOWNLOADED -> {
                loadDownloadedImages()
            }
            GalleryMode.CAMERA_SD -> {
                if (mediaManager != null && !cameraId.isNullOrEmpty()) {
                    viewModelScope.launch {
                        _cameraSdLoading.value = true
                        try {
                            // Load cached dates and previews
                            loadCachedDates()

                            // Now scan the DCIM directory fresh
                            val dcimUri = "storage:memoryCard1"
                            val dcimContents = loadMediaSingleDirectory(dcimUri)
                            val dateFolders = dcimContents.filter { it.contentKind.equals("directory", ignoreCase = true) }
                            val freshDates = dateFolders.mapNotNull { dir ->
                                val uri = dir.uri
                                val datePart = uri.substringAfter("?path=", missingDelimiterValue = "")
                                datePart.takeIf { it.matches(Regex("""\d{4}-\d{2}-\d{2}""")) }
                            }.sortedDescending()

                            Log.d("MediaManager", "Found date folders: $freshDates")

                            // Update cached dates with the fresh ones
                            _cachedDates.value = freshDates

                            // Persist fresh dates to disk cache
                            mediaManager?.saveCacheToDisk(
                                cameraId = cameraId,
                                cachedDates = freshDates,
                                contentForDate = emptyMap(), // no new metadata being updated at this point
                                selectedDate = null
                            )

                        } catch (e: Exception) {
                            Log.e("GalleryViewModel", "Failed to load Camera SD cache: ${e.localizedMessage}")
                            _cachedDates.value = emptyList()
                            _datePreviews.value = emptyMap()
                        } finally {
                            _cameraSdLoading.value = false
                        }
                    }
                } else {
                    Log.w("GalleryViewModel", "Cannot enter Camera SD mode: no camera connected or cameraId is empty")
                    _cachedDates.value = emptyList()
                    _datePreviews.value = emptyMap()
                }
            }
            else -> {
                // no-op
            }
        }
    }

    fun setCameraSdViewType(type: CameraSdViewType) {
        _cameraSdViewType.value = type
    }

    // -------------- Data Fetching ----------------

    // Load cached date keys from MediaManager
    fun loadCachedDates() {
        val id = cameraId ?: return
        viewModelScope.launch {
            val mm = mediaManager ?: return@launch
            val dateKeys: List<String> = mm.getCachedDates(id)
            val sortedDateKeys = dateKeys.sortedDescending()
            _cachedDates.value = sortedDateKeys

            // Just load cached metadata for quick previews (optional)
            val previewsMap = mutableMapOf<String, List<CapturedImage>>()
            for (date in sortedDateKeys) {
                val cachedMetadata: List<ContentItem> = mm.loadMetadataForDate(id, date) ?: emptyList()
                val capturedList = cachedMetadata.map { mm.contentToCaptured(it) }
                    .sortedBy { it.timestamp }
                previewsMap[date] = capturedList.take(4)
            }
            _datePreviews.value = previewsMap
        }
    }

    fun onDateGroupVisible(dateKey: String) {
        if (_loadingDates.value.contains(dateKey) || _loadedDates.contains(dateKey)) return

        _loadingDates.value = _loadingDates.value + dateKey

        viewModelScope.launch {
            loadMetadataForDate(dateKey)
            _loadingDates.value = _loadingDates.value - dateKey
            _loadedDates.add(dateKey)
        }
    }

    fun loadPreviewMetadataIfCached(dateKey: String) {
        if (_loadedDates.contains(dateKey)) return

        viewModelScope.launch {
            val mm = mediaManager ?: return@launch
            val camId = cameraId ?: return@launch

            val contents = mm.loadMetadataForDate(camId, dateKey)

            if (contents.isNotEmpty()) {
                val captured = contents.map { mm.contentToCaptured(it) }.sortedBy { it.timestamp }
                _contentForDate.value = captured
                updatePreviewForDate(dateKey, captured)
                _loadedDates.add(dateKey)
            } else {
                // Nothing cached — fallback to full load from camera
                onDateGroupVisible(dateKey)
            }
        }
    }

    fun updatePreviewForDate(dateKey: String, capturedList: List<CapturedImage>) {
        val currentPreviews = _datePreviews.value.toMutableMap()
        currentPreviews[dateKey] = capturedList.take(4)
        _datePreviews.value = currentPreviews
    }

    suspend fun loadMetadataForDate(dateKey: String) {
        if (cameraId == null) return

        val mm = mediaManager ?: return

        val dateUri = "storage:memoryCard1/$dateKey"
        val contents = loadMediaSingleDirectory(dateUri)
        val capturedList = contents.map { mm.contentToCaptured(it) }
            .sortedBy { it.timestamp }

        _contentForDate.value = capturedList
        updatePreviewForDate(dateKey, capturedList)
        prefetchThumbnailsIfNeeded(capturedList)

        mm.saveCacheToDisk(
            cameraId = cameraId,
            cachedDates = _cachedDates.value,
            contentForDate = mapOf(dateKey to capturedList),
            selectedDate = dateKey
        )
    }

    private suspend fun loadMediaSingleDirectory(
        uri: String,
        pageSize: Int = 100
    ): List<ContentItem> {
        if (mediaManager == null || cameraController == null) {
            Log.e("GalleryViewModel", "Camera not connected. Cannot load media.")
            return emptyList()
        }

        val allContents = mutableListOf<ContentItem>()
        var startIndex = 0

        while (true) {
            val contents = cameraController.getContentList(uri = uri, stIdx = startIndex, cnt = pageSize)
            if (contents.isEmpty()) break

            // No filtering by seenUris anymore:
            allContents.addAll(contents)

            if (contents.size < pageSize) break
            startIndex += pageSize
        }

        return allContents
    }

    fun loadCameraSdImagesFullFresh() {
        if (mediaManager == null || cameraController == null || cameraId.isNullOrBlank()) {
            Log.e("GalleryViewModel", "mediaManager, cameraController, or cameraId is null. Cannot load camera SD images.")
            return
        }

        viewModelScope.launch {
            _cameraSdLoading.value = true
            try {
                val rootUri = "storage:memoryCard1"

                // Clear old caches
                mediaManager.seenUris.clear()
                mediaManager.cachedImagesByDate.clear()
                mediaManager.cachedVideosByDate.clear()

                val allImages = mutableListOf<ContentItem>()
                val allVideos = mutableListOf<ContentItem>()

                // Recursive traversal
                suspend fun loadAll(uri: String, currentDepth: Int = 0, maxDepth: Int = 10) {
                    if (currentDepth > maxDepth) return

                    var startIndex = 0
                    val pageSize = 100

                    while (true) {
                        val contents = cameraController.getContentList(uri = uri, stIdx = startIndex, cnt = pageSize)
                        if (contents.isEmpty()) break

                        allImages.addAll(contents.filter { it.contentKind.equals("still", ignoreCase = true) })
                        allVideos.addAll(contents.filter { it.contentKind.equals("video", ignoreCase = true) })

                        val directories = contents.filter { it.contentKind.equals("directory", ignoreCase = true) }
                        for (dir in directories) {
                            loadAll(dir.uri, currentDepth + 1, maxDepth)
                        }

                        if (contents.size < pageSize) break
                        startIndex += pageSize
                    }
                }

                loadAll(rootUri)

                // Group by date
                val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC)

                fun getDateKey(item: ContentItem): String {
                    val ts = item.timestamp ?: System.currentTimeMillis()
                    return dateFormatter.format(Instant.ofEpochMilli(ts))
                }

                val imagesByDate = mutableMapOf<String, MutableList<CapturedImage>>()
                val videosByDate = mutableMapOf<String, MutableList<CapturedImage>>() // In case you later want to support videos

                allImages.forEach { item ->
                    val dateKey = getDateKey(item)
                    val captured = mediaManager.contentToCaptured(item)
                    imagesByDate.getOrPut(dateKey) { mutableListOf() }.add(captured)
                    mediaManager.seenUris.add(item.uri)
                }

                allVideos.forEach { item ->
                    val dateKey = getDateKey(item)
                    val captured = mediaManager.contentToCaptured(item)
                    videosByDate.getOrPut(dateKey) { mutableListOf() }.add(captured)
                    mediaManager.seenUris.add(item.uri)
                }

                mediaManager.cachedImagesByDate = imagesByDate
                mediaManager.cachedVideosByDate = videosByDate

                mediaManager.clearCache(cameraId)
                mediaManager.saveCacheToDiskFull(
                    cameraId = cameraId,
                    cachedDates = imagesByDate.keys.toList(),
                    contentForDate = imagesByDate
                )

                Log.d("GalleryViewModel", "Full refresh load complete for camera SD images/videos")
            } catch (e: Exception) {
                Log.e("GalleryViewModel", "Failed to fully load Camera SD media: ${e.localizedMessage}")
            } finally {
                _cameraSdLoading.value = false
            }
        }
    }

    fun selectDate(dateKey: String?) {
        if (dateKey == null) {
            _selectedDate.value = null
            _contentForDate.value = emptyList()
            _isSelectedDateLoading.value = false
            return
        }

        if (_isSelectedDateLoading.value) return

        _selectedDate.value = dateKey
        _isSelectedDateLoading.value = true

        viewModelScope.launch {
            try {
                loadMetadataForDate(dateKey)
            } finally {
                _isSelectedDateLoading.value = false
            }
        }
    }

    private fun loadDownloadedImages(folderRelativePath: String = "DCIM/SonyRX10M3Remote/") {
        _downloadedLoading.value = true
        viewModelScope.launch {
            val uris = mediaStoreHelper.loadDownloadedImages(folderRelativePath)
            Log.d("GalleryViewModel", "Loaded ${uris.size} downloaded images")
            _downloadedImages.value = uris.map { uri ->
                CapturedImage(
                    uri = uri.toString(),
                    localUri = uri
                )
            }
        }
        _downloadedLoading.value = false
    }

    fun updateImage(updated: CapturedImage) {
        _contentForDate.update { currentList ->
            currentList.map { existing ->
                if (existing.uri == updated.uri) updated else existing
            }
        }
    }

    fun requestThumbnailIfNeeded(image: CapturedImage) {
        val currentId = cameraId ?: return
        mediaManager?.requestThumbnailDownload(currentId, image) { updated ->
            updateImage(updated)
        }
    }

    fun prefetchThumbnailsIfNeeded(images: List<CapturedImage>) {
        for (image in images) {
            requestThumbnailIfNeeded(image)
        }
    }

    fun clearCacheAndReset(cameraId: String) {
        viewModelScope.launch {
            // Save any unsaved cache before wiping
            mediaManager?.saveCacheToDisk(
                cameraId = cameraId,
                cachedDates = _cachedDates.value,
                mapOf(_selectedDate.value!! to _contentForDate.value),
                selectedDate = _selectedDate.value // or omit this; it's used only to avoid reloading from disk
            )

            // Now clear in-memory state
            _cachedDates.value = emptyList()
            _datePreviews.value = emptyMap()
            _contentForDate.value = emptyList()
            _selectedDate.value = null

            // Then clear disk + MediaManager-level state
            mediaManager?.clearCache(cameraId)
        }
    }

    // -------------- Intervalometer ---------------

    fun processCapturedImages(images: List<CapturedImage>) {
        Log.d("GalleryViewModel", "Received ${images.size} interval captures")
        _capturedImages.value = images
        _showIntervalPopup.value = true
    }

    // Call this to dismiss the popup when user closes it
    fun dismissIntervalPopup() {
        viewModelScope.launch {
            // Hide popup and clear selected images
            _isDownloading.value = false
            _showIntervalPopup.value = false
            _capturedImages.value = emptyList()
            _chosenImages.value = emptySet()

            if (_mode.value != GalleryMode.CAMERA_SD) {
                onGalleryClosed()
            }
        }
    }

    // Toggle selection for a given image
    fun updateChosenImage(image: CapturedImage, selected: Boolean) {
        val current = _chosenImages.value.toMutableSet()
        if (selected) current.add(image) else current.remove(image)
        _chosenImages.value = current
    }

    // Trigger downloads for selected images
    fun downloadChosenImages() {
        viewModelScope.launch {
            _isDownloading.value = true

            _chosenImages.value.forEach { capturedImage ->
                val mediaInfo = capturedImageToMediaInfo(capturedImage)
                if (mediaInfo != null) {
                    // Construct CapturedImage first, as done in onPhotoCaptured
                    val sessionImage = CapturedImage(
                        uri = mediaInfo.fileName,
                        remoteUrl = mediaInfo.fullImageUrl,
                        lastModified = System.currentTimeMillis()
                    )

                    // Notify the session gallery
                    mediaManager?.sessionImageListener?.onNewSessionImage(sessionImage)

                    // Then trigger download
                    mediaManager?.processBatchDownload(mediaInfo)
                }
            }

            dismissIntervalPopup()
        }
    }

    // Helper to build a MediaInfo object for mediaManager
    private fun capturedImageToMediaInfo(image: CapturedImage): MediaInfo? {
        val remoteUrl = image.remoteUrl ?: return null

        // Generate a simple, valid filename
        val fileName = generateSafeFilename()

        return MediaInfo(
            previewUrl = remoteUrl,
            fullImageUrl = remoteUrl,
            fileName = fileName,
            isVideo = false
        )
    }

    fun generateSafeFilename(): String {
        val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val timestamp = dateFormat.format(Date())
        val unique = System.currentTimeMillis().toString().takeLast(4)
        val prefix = "RX10M3_"
        val extension = ".jpg"
        return "${prefix}_${timestamp}_$unique.$extension"
    }

}

fun formatDateKey(dateKey: String): String {
    return try {
        val parsedDate = LocalDate.parse(dateKey) // ISO_LOCAL_DATE by default
        val formatter = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH)
        parsedDate.format(formatter)  // e.g. "14 June 2025"
    } catch (e: Exception) {
        dateKey // fallback to raw string if parse fails
    }
}

class GalleryViewModelFactory(
    private val context: Context,
    private val mediaManager: MediaManager?,
    private val cameraController: CameraController?,
    private val cameraId: String?
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(GalleryViewModel::class.java)) {
            return GalleryViewModel(context, mediaManager, cameraController, cameraId) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

//// Iteratively runs getContentList() to search through the directory structure of camera storage for image/video files
//fun loadCameraSdImages() {
//    if (mediaManager == null || cameraController == null || cameraId.isNullOrBlank()) {
//        Log.e("GalleryViewModel", "mediaManager is null. Cannot load camera SD images.")
//        return
//    }
//
//    viewModelScope.launch {
//        _cameraSdLoading.value = true
//        try {
//            val rootUri = "storage:memoryCard1"
//            val newImages = mutableListOf<ContentItem>()
//            val newVideos = mutableListOf<ContentItem>()
//
//            loadMediaRecursively(
//                uri = rootUri,
//                images = newImages,
//                videos = newVideos
//            )
//
//            // Add new media items to cache and mark them seen
//            mediaManager.addToCache(newImages, newVideos)
//
//            // Update state flows with sorted + mapped images/videos
//            _cameraSdImages.value = mediaManager.cachedImages
//                .asSequence()
//                .distinctBy { it.uri }
//                .sortedBy { it.timestamp }
//                .map { mediaManager.contentToCaptured(it) }
//                .toList()
//
//            _cameraSdVideos.value = mediaManager.cachedVideos
//                .asSequence()
//                .distinctBy { it.uri }
//                .sortedBy { it.timestamp }
//                .map { mediaManager.contentToCaptured(it) }
//                .toList()
//
//            // Persist to disk if we have a cameraId
//            if (!cameraId.isNullOrBlank()) {
//                mediaManager.saveLiveCacheToDisk(cameraId)
//            } else {
//                Log.w("GalleryViewModel", "cameraId is null/blank; skipping disk cache save")
//            }
//
//
//            // Now download missing thumbnails for camera SD images, update state as they arrive
//            launch {
//                mediaManager.downloadMissingThumbnails(cameraId, _cameraSdImages.value) { updatedImage ->
//                    val updatedList = _cameraSdImages.value.map {
//                        if (it.uri == updatedImage.uri) updatedImage else it
//                    }
//                    _cameraSdImages.value = updatedList
//
//                    // Save updated metadata to disk
//                    launch {
//                        mediaManager.saveLiveCacheToDisk(cameraId)
//                    }
//                }
//            }
//        } catch (e: Exception) {
//            Log.e("GalleryViewModel", "Failed to load Camera SD media: ${e.localizedMessage}")
//            _cameraSdImages.value = emptyList()
//            _cameraSdVideos.value = emptyList()
//        } finally {
//            _cameraSdLoading.value = false
//        }
//    }
//}
//
//// Helper function to for recursive search through getContentList
//private suspend fun loadMediaRecursively(
//    uri: String,
//    images: MutableList<ContentItem>,
//    videos: MutableList<ContentItem>,
//    currentDepth: Int = 0,
//    maxDepth: Int = 10,
//    pageSize: Int = 100
//) {
//    if (mediaManager == null || cameraController == null) {
//        Log.e("GalleryViewModel", "Camera not connected. Cannot load camera SD images.")
//        return
//    }
//
////        Log.d("GalleryViewModel", "Recursing into $uri at depth $currentDepth")
//
//    if (currentDepth > maxDepth) {
////            Log.w("GalleryViewModel", "Max recursion depth reached at URI: $uri")
//        return
//    }
//
//    val alreadyVisited = uri in mediaManager.seenUris
//    var startIndex = 0
//    val directories = mutableListOf<ContentItem>()
//    var lastDir: ContentItem? = null
//
//    while (true) {
//        val contents = cameraController.getContentList(uri = uri, stIdx = startIndex, cnt = pageSize)
////            Log.d("GalleryViewModel", "getContentList($uri, $startIndex) returned ${contents.size} items")
//
//        if (contents.isEmpty()) {
////                Log.d("GalleryViewModel", "No more contents at $uri, breaking loop")
//            break
//        }
//
//        val stills = contents.filter { it.contentKind.equals("still", ignoreCase = true) }
//        val newStills = stills.filter { item ->
//            val isNew = item.uri !in mediaManager.seenUris
//            if (isNew) mediaManager.seenUris.add(item.uri)
//            isNew
//        }
//
//        val vids = contents.filter { it.contentKind.equals("video", ignoreCase = true) }
//        val newVideos = vids.filter { item ->
//            val isNew = item.uri !in mediaManager.seenUris
//            if (isNew) mediaManager.seenUris.add(item.uri)
//            isNew
//        }
//
//        val dirs = contents.filter { it.contentKind.equals("directory", ignoreCase = true) }
//        if (dirs.isNotEmpty()) {
//            lastDir = dirs.last()
//        }
//
////            Log.d("GalleryViewModel", "Found newStills=${newStills.size}, newVideos=${newVideos.size}, directories=${dirs.size}")
//
//        images.addAll(newStills)
//        videos.addAll(newVideos)
//
//        if (alreadyVisited) {
////                Log.d("GalleryViewModel", "Revisited $uri and found ${newStills.size} new stills, ${newVideos.size} new videos")
//        }
//
//        directories.addAll(dirs)
//
//        if (contents.size < pageSize) {
////                Log.d("GalleryViewModel", "Last page of contents received (< pageSize), breaking loop")
//            break
//        }
//
//        startIndex += pageSize
//    }
//
//    // Mark this directory as visited
//    if (!alreadyVisited) {
//        mediaManager.seenUris.add(uri)
//    }
//
//    // Recurse into all directories unless seen — but always include the lastDir
//    val dirsToVisit = directories.distinctBy { it.uri }.filter { dir ->
//        val isLast = (dir == lastDir)
//        val notSeen = dir.uri !in mediaManager.seenUris
//        notSeen || isLast
//    }
//
//    for (dir in dirsToVisit) {
////            Log.d("GalleryViewModel", "Recursing into subdirectory: ${dir.uri}")
//        loadMediaRecursively(dir.uri, images, videos, currentDepth + 1, maxDepth, pageSize)
//    }
//}