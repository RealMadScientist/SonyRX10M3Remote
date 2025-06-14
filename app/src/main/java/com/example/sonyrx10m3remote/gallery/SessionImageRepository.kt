package com.example.sonyrx10m3remote.gallery

import android.net.Uri
import android.util.Log
import com.example.sonyrx10m3remote.data.CapturedImage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object SessionImageRepository {
    private const val TAG = "SessionImageRepository"

    private val _sessionImages = MutableStateFlow<List<CapturedImage>>(emptyList())
    val sessionImages: StateFlow<List<CapturedImage>> = _sessionImages

    var restartLiveViewCallback: (() -> Unit)? = null

    fun addImages(images: List<CapturedImage>) {
        val current = _sessionImages.value
        val combined = (current + images).distinctBy { it.uri }
            .sortedBy { it.timestamp }

        Log.d(TAG, "Added ${images.size} images. Total: ${combined.size}")
        combined.forEach {
            Log.d(TAG, "Image id=${it.uri} ts=${it.timestamp} localUri=${it.localUri}")
        }

        _sessionImages.value = combined
    }

    fun updateImageUri(imageId: String, newUri: Uri) {
        val current = _sessionImages.value
        val updated = current.map { image ->
            if (image.uri == imageId) {
                Log.d(TAG, "Updating image URI: $imageId -> $newUri")
                image.copy(localUri = newUri)
            } else {
                image
            }
        }

        _sessionImages.value = updated
        Log.d(TAG, "Session images updated with new URI for $imageId")
    }
}