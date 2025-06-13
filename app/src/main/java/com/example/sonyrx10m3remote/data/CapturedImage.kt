package com.example.sonyrx10m3remote.data

import android.net.Uri
import com.example.sonyrx10m3remote.camera.CameraController.ContentItem

data class CapturedImage(
    val uri: String,
    val remoteUrl: String? = null,
    val thumbnailUrl: String? = null,
    val localUri: Uri? = null,
    val timestamp: Long = 0L,
    val downloaded: Boolean = false,
    val fileName: String? = null,
    val contentKind: String? = null,
    val lastModified: Long = System.currentTimeMillis()
)