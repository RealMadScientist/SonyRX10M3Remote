package com.example.sonyrx10m3remote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.sonyrx10m3remote.camera.CameraControllerProvider
import com.example.sonyrx10m3remote.gallery.GalleryScreen
import com.example.sonyrx10m3remote.gallery.GalleryViewModel
import com.example.sonyrx10m3remote.gallery.GalleryViewModelFactory
import com.example.sonyrx10m3remote.ui.theme.RX10M3RemoteTheme

class GalleryActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val mediaManager = MediaManagerProvider.instance
            ?: error("MediaManager is not initialized")

        val cameraController = CameraControllerProvider.instance
            ?: throw IllegalStateException("CameraController instance is null after initialization")

        setContent {
            RX10M3RemoteTheme {
                val viewModel: GalleryViewModel = viewModel(
                    factory = GalleryViewModelFactory(applicationContext, mediaManager, cameraController)
                )

                GalleryScreen(viewModel = viewModel,
                    cameraController = CameraControllerProvider.instance)
            }
        }
    }
}