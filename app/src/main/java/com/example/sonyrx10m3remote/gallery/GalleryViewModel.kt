package com.example.sonyrx10m3remote.gallery

import android.content.Context
import android.net.Uri
import android.util.Log
import android.widget.ImageView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.sonyrx10m3remote.media.MediaManager
import com.example.sonyrx10m3remote.media.MediaManager.MediaInfo
import com.example.sonyrx10m3remote.media.MediaStoreHelper
import com.example.sonyrx10m3remote.camera.CameraController
import com.example.sonyrx10m3remote.camera.CameraController.ContentItem
import com.example.sonyrx10m3remote.data.CapturedImage
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeoutException

enum class GalleryMode {
    SESSION,
    CAMERA_SD,
    DOWNLOADED
}

enum class CameraSdViewType {
    IMAGES,
    VIDEOS
}

class GalleryViewModel(
    private val context: Context,
    private val mediaManager: MediaManager?,
    private val cameraController: CameraController?,
    private val cameraId: String?
) : ViewModel() {
    private val mediaStoreHelper = MediaStoreHelper(context)

    // StateFlows holding images from Session, Camera SD and Downloaded modes
    private val _sessionImages = MutableStateFlow<List<CapturedImage>>(emptyList())
    val sessionImages: StateFlow<List<CapturedImage>> = _sessionImages
    private val _cameraSdImages = MutableStateFlow<List<CapturedImage>>(emptyList())
    val cameraSdImages: StateFlow<List<CapturedImage>> = _cameraSdImages
    private val _cameraSdVideos = MutableStateFlow<List<CapturedImage>>(emptyList())
    val cameraSdVideos: StateFlow<List<CapturedImage>> = _cameraSdVideos
    private val _downloadedImages = MutableStateFlow<List<CapturedImage>>(emptyList())
    val downloadedImages: StateFlow<List<CapturedImage>> = _downloadedImages

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

            // If a camera is connected, load the associated disk cache
            if (mediaManager != null && !cameraId.isNullOrEmpty()) {
                try {
                    mediaManager.loadDiskCacheIntoLive(cameraId!!)
                    // Update UI StateFlows so cached thumbnails show immediately:
                    _cameraSdImages.value = mediaManager.cachedImages
                        .asSequence()
                        .distinctBy { it.uri }
                        .sortedBy { it.timestamp }
                        .map { mediaManager.contentToCaptured(it) }
                        .toList()
                    _cameraSdVideos.value = mediaManager.cachedVideos
                        .asSequence()
                        .distinctBy { it.uri }
                        .sortedBy { it.timestamp }
                        .map { mediaManager.contentToCaptured(it) }
                        .toList()
                } catch (e: Exception) {
                    Log.e("GalleryViewModel", "Error loading disk cache: ${e.localizedMessage}")
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
            GalleryMode.DOWNLOADED -> loadDownloadedImages()
            GalleryMode.CAMERA_SD -> loadCameraSdImages()
            else -> {}
        }
    }

    fun setCameraSdViewType(type: CameraSdViewType) {
        _cameraSdViewType.value = type
    }

    // Iteratively runs getContentList() to search through the directory structure of camera storage for image/video files
    fun loadCameraSdImages() {
        if (mediaManager == null || cameraController == null || cameraId.isNullOrBlank()) {
            Log.e("GalleryViewModel", "mediaManager is null. Cannot load camera SD images.")
            return
        }

        viewModelScope.launch {
            _cameraSdLoading.value = true
            try {
                val rootUri = "storage:memoryCard1"
                val newImages = mutableListOf<ContentItem>()
                val newVideos = mutableListOf<ContentItem>()

                loadMediaRecursively(
                    uri = rootUri,
                    images = newImages,
                    videos = newVideos
                )

                // Add new media items to cache and mark them seen
                mediaManager.addToCache(newImages, newVideos)

                // Update state flows with sorted + mapped images/videos
                _cameraSdImages.value = mediaManager.cachedImages
                    .asSequence()
                    .distinctBy { it.uri }
                    .sortedBy { it.timestamp }
                    .map { mediaManager.contentToCaptured(it) }
                    .toList()

                _cameraSdVideos.value = mediaManager.cachedVideos
                    .asSequence()
                    .distinctBy { it.uri }
                    .sortedBy { it.timestamp }
                    .map { mediaManager.contentToCaptured(it) }
                    .toList()

                // Persist to disk if we have a cameraId
                if (!cameraId.isNullOrBlank()) {
                    mediaManager.saveLiveCacheToDisk(cameraId)
                } else {
                    Log.w("GalleryViewModel", "cameraId is null/blank; skipping disk cache save")
                }


                // Now download missing thumbnails for camera SD images, update state as they arrive
                launch {
                    mediaManager.downloadMissingThumbnails(cameraId, _cameraSdImages.value) { updatedImage ->
                        // Update the list by replacing the image with updated localUri
                        val updatedList = _cameraSdImages.value.map {
                            if (it.uri == updatedImage.uri) updatedImage else it
                        }
                        _cameraSdImages.value = updatedList
                    }
                }
            } catch (e: Exception) {
                Log.e("GalleryViewModel", "Failed to load Camera SD media: ${e.localizedMessage}")
                _cameraSdImages.value = emptyList()
                _cameraSdVideos.value = emptyList()
            } finally {
                _cameraSdLoading.value = false
            }
        }
    }

    // Helper function to for recursive search through getContentList
    private suspend fun loadMediaRecursively(
        uri: String,
        images: MutableList<ContentItem>,
        videos: MutableList<ContentItem>,
        currentDepth: Int = 0,
        maxDepth: Int = 10,
        pageSize: Int = 100
    ) {
        if (mediaManager == null || cameraController == null) {
            Log.e("GalleryViewModel", "Camera not connected. Cannot load camera SD images.")
            return
        }

//        Log.d("GalleryViewModel", "Recursing into $uri at depth $currentDepth")

        if (currentDepth > maxDepth) {
//            Log.w("GalleryViewModel", "Max recursion depth reached at URI: $uri")
            return
        }

        val alreadyVisited = uri in mediaManager.seenUris
        var startIndex = 0
        val directories = mutableListOf<ContentItem>()
        var lastDir: ContentItem? = null

        while (true) {
            val contents = cameraController.getContentList(uri = uri, stIdx = startIndex, cnt = pageSize)
//            Log.d("GalleryViewModel", "getContentList($uri, $startIndex) returned ${contents.size} items")

            if (contents.isEmpty()) {
//                Log.d("GalleryViewModel", "No more contents at $uri, breaking loop")
                break
            }

            val stills = contents.filter { it.contentKind.equals("still", ignoreCase = true) }
            val newStills = stills.filter { item ->
                val isNew = item.uri !in mediaManager.seenUris
                if (isNew) mediaManager.seenUris.add(item.uri)
                isNew
            }

            val vids = contents.filter { it.contentKind.equals("video", ignoreCase = true) }
            val newVideos = vids.filter { item ->
                val isNew = item.uri !in mediaManager.seenUris
                if (isNew) mediaManager.seenUris.add(item.uri)
                isNew
            }

            val dirs = contents.filter { it.contentKind.equals("directory", ignoreCase = true) }
            if (dirs.isNotEmpty()) {
                lastDir = dirs.last()
            }

//            Log.d("GalleryViewModel", "Found newStills=${newStills.size}, newVideos=${newVideos.size}, directories=${dirs.size}")

            images.addAll(newStills)
            videos.addAll(newVideos)

            if (alreadyVisited) {
//                Log.d("GalleryViewModel", "Revisited $uri and found ${newStills.size} new stills, ${newVideos.size} new videos")
            }

            directories.addAll(dirs)

            if (contents.size < pageSize) {
//                Log.d("GalleryViewModel", "Last page of contents received (< pageSize), breaking loop")
                break
            }

            startIndex += pageSize
        }

        // Mark this directory as visited
        if (!alreadyVisited) {
            mediaManager.seenUris.add(uri)
        }

        // Recurse into all directories unless seen — but always include the lastDir
        val dirsToVisit = directories.distinctBy { it.uri }.filter { dir ->
            val isLast = (dir == lastDir)
            val notSeen = dir.uri !in mediaManager.seenUris
            notSeen || isLast
        }

        for (dir in dirsToVisit) {
//            Log.d("GalleryViewModel", "Recursing into subdirectory: ${dir.uri}")
            loadMediaRecursively(dir.uri, images, videos, currentDepth + 1, maxDepth, pageSize)
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

    fun clear() {
        _sessionImages.value = emptyList()
        _selectedImage.value = null
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

            onGalleryClosed()
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