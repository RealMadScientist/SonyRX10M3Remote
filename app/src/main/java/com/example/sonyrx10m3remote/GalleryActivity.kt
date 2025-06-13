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
        val cameraController = CameraControllerProvider.instance

        val cameraId = intent.getStringExtra("camera_id")

        setContent {
            RX10M3RemoteTheme {
                val viewModel: GalleryViewModel = viewModel(
                    factory = GalleryViewModelFactory(applicationContext, mediaManager, cameraController, cameraId)
                )

                GalleryScreen(viewModel = viewModel,
                    cameraController = CameraControllerProvider.instance,
                    mediaManager = MediaManagerProvider.instance,
                    cameraId = cameraId)
            }
        }
    }
}