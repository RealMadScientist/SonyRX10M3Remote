package com.example.sonyrx10m3remote.gallery

import android.content.Context
import android.net.Uri
import android.util.Log
import android.widget.ImageView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.sonyrx10m3remote.R
import com.example.sonyrx10m3remote.media.MediaStoreHelper
import com.example.sonyrx10m3remote.camera.CameraController
import com.example.sonyrx10m3remote.camera.CameraController.ContentItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collect

data class CapturedImage(
    val id: String,
    val remoteUrl: String? = null,
    val thumbnailUrl: String? = null,
    val localUri: Uri? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val downloaded: Boolean = false,
    val filename: String? = null
)

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
    private val context: Context
) : ViewModel() {
    private val mediaStoreHelper = MediaStoreHelper(context)

    private val _sessionImages = MutableStateFlow<List<CapturedImage>>(emptyList())
    val sessionImages: StateFlow<List<CapturedImage>> = _sessionImages

    private val _cameraSdImages = MutableStateFlow<List<CapturedImage>>(emptyList())
    val cameraSdImages: StateFlow<List<CapturedImage>> = _cameraSdImages

    private val _cameraSdVideos = MutableStateFlow<List<CapturedImage>>(emptyList())
    val cameraSdVideos: StateFlow<List<CapturedImage>> = _cameraSdVideos

    private val _cameraSdViewType = MutableStateFlow(CameraSdViewType.IMAGES)
    val cameraSdViewType: StateFlow<CameraSdViewType> = _cameraSdViewType

    private val _downloadedImages = MutableStateFlow<List<CapturedImage>>(emptyList())
    val downloadedImages: StateFlow<List<CapturedImage>> = _downloadedImages

    private val _selectedImage = MutableStateFlow<CapturedImage?>(null)
    val selectedImage: StateFlow<CapturedImage?> = _selectedImage

    private val _mode = MutableStateFlow(GalleryMode.SESSION)
    val mode: StateFlow<GalleryMode> = _mode

    private val seenUris = mutableSetOf<String>()
    private val cachedImages = mutableListOf<ContentItem>()
    private val cachedVideos = mutableListOf<ContentItem>()
    private val visitedDirs = mutableSetOf<String>()

    init {
        viewModelScope.launch {
            SessionImageRepository.sessionImages.collect { sessionList ->
                Log.d("GalleryViewModel", "Session images updated: ${sessionList.size} images")
                _sessionImages.value = sessionList
            }
        }
    }

    fun onGalleryOpened(cameraController: CameraController?) {
        if (cameraController == null) return

        viewModelScope.launch {
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

    fun onGalleryClosed(cameraController: CameraController?) {
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
                withContext(NonCancellable) {
                    val liveViewUrl = cameraController.startLiveView()
                }
            } catch (e: Exception) {
                Log.e("GalleryViewModel", "Error starting live view: ${e.localizedMessage}")
            }
        }
    }

    fun selectImage(image: CapturedImage?) {
        _selectedImage.value = image
    }

    fun setMode(newMode: GalleryMode, cameraController: CameraController? = null) {
        _mode.value = newMode
        when (newMode) {
            GalleryMode.DOWNLOADED -> loadDownloadedImages()
            GalleryMode.CAMERA_SD -> cameraController?.let { loadCameraSdImages(it) }
            else -> {}
        }
    }

    fun setCameraSdViewType(type: CameraSdViewType) {
        _cameraSdViewType.value = type
    }

    // Iteratively runs getContentList() to search through the directory structure of camera storage for image/video files
    fun loadCameraSdImages(cameraController: CameraController) {
        viewModelScope.launch {
            try {
                visitedDirs.clear()

                val rootUri = "storage:memoryCard1"
                val newImages = mutableListOf<ContentItem>()
                val newVideos = mutableListOf<ContentItem>()

                loadMediaRecursively(
                    cameraController = cameraController,
                    uri = rootUri,
                    images = newImages,
                    videos = newVideos
                )

                // Only keep unseen content
                val uniqueNewImages = newImages.filter { it.uri !in seenUris }
                val uniqueNewVideos = newVideos.filter { it.uri !in seenUris }

                // Update cache and seen list
                seenUris.addAll(uniqueNewImages.map { it.uri })
                seenUris.addAll(uniqueNewVideos.map { it.uri })
                cachedImages.addAll(uniqueNewImages)
                cachedVideos.addAll(uniqueNewVideos)

                // Update state
                _cameraSdImages.value = cachedImages
                    .sortedBy { it.lastModified }
                    .map { item ->
                        CapturedImage(
                            id = item.uri,
                            remoteUrl = item.remoteUrl,
                            thumbnailUrl = item.thumbnailUrl,
                            filename = item.fileName
                        )
                    }

                _cameraSdVideos.value = cachedVideos
                    .sortedBy { it.lastModified }
                    .map { item ->
                        CapturedImage(
                            id = item.uri,
                            remoteUrl = item.remoteUrl,
                            thumbnailUrl = item.thumbnailUrl,
                            filename = item.fileName
                        )
                    }

            } catch (e: Exception) {
                Log.e("GalleryViewModel", "Failed to load Camera SD media: ${e.localizedMessage}")
                _cameraSdImages.value = emptyList()
                _cameraSdVideos.value = emptyList()
            }
        }
    }

    // Helper function to for recursive search through getContentList
    private suspend fun loadMediaRecursively(
        cameraController: CameraController,
        uri: String,
        images: MutableList<ContentItem>,
        videos: MutableList<ContentItem>,
        currentDepth: Int = 0,
        maxDepth: Int = 10,
        pageSize: Int = 100
    ) {
        Log.d("GalleryViewModel", "Recursing into $uri at depth $currentDepth")
        if (uri in visitedDirs) {
            Log.w("GalleryViewModel", "Already visited $uri, skipping")
            return
        }
        visitedDirs.add(uri)

        if (currentDepth > maxDepth) {
            Log.w("GalleryViewModel", "Max recursion depth reached at URI: $uri")
            return
        }

        val directories = mutableListOf<ContentItem>()
        var startIndex = 0

        while (true) {
            val contents = cameraController.getContentList(uri = uri, stIdx = startIndex, cnt = pageSize)
            Log.d("GalleryViewModel", "getContentList($uri, $startIndex, $pageSize) returned ${contents.size} items")

            if (contents.isEmpty()) {
                Log.d("GalleryViewModel", "No more contents at $uri, breaking loop")
                break
            }

            val stills = contents.filter { it.contentKind.equals("still", ignoreCase = true) }
            val vids = contents.filter { it.contentKind.equals("video", ignoreCase = true) }
            val newDirs = contents.filter { it.contentKind.equals("directory", ignoreCase = true) }

            val newStills = stills.filter { it.uri !in seenUris }
            val newVideos = vids.filter { it.uri !in seenUris }

            Log.d("GalleryViewModel", "Found newStills=${newStills.size}, newVideos=${newVideos.size}, newDirs=${newDirs.size}")

            // Bail out only if no new images/videos AND no directories
            if (newStills.isEmpty() && newVideos.isEmpty() && newDirs.isEmpty()) {
                Log.d("GalleryViewModel", "No new media or directories found, breaking loop")
                break
            }

            images.addAll(newStills)
            videos.addAll(newVideos)
            directories.addAll(newDirs)

            if (contents.size < pageSize) {
                Log.d("GalleryViewModel", "Last page of contents received (< pageSize), breaking loop")
                break
            }

            startIndex += pageSize
        }

        for (dir in directories) {
            Log.d("GalleryViewModel", "Recursing into subdirectory: ${dir.uri}")
            loadMediaRecursively(cameraController, dir.uri, images, videos, currentDepth + 1, maxDepth, pageSize)
        }
    }

    private fun loadDownloadedImages(folderRelativePath: String = "DCIM/SonyRX10M3Remote/") {
        viewModelScope.launch {
            val uris = mediaStoreHelper.loadDownloadedImages(folderRelativePath)
            _downloadedImages.value = uris.map { uri ->
                CapturedImage(
                    id = uri.toString(),
                    localUri = uri
                )
            }
        }
    }

    fun clear() {
        _sessionImages.value = emptyList()
        _selectedImage.value = null
    }
}

class GalleryViewModelFactory(
    private val context: Context
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(GalleryViewModel::class.java)) {
            return GalleryViewModel(context) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}