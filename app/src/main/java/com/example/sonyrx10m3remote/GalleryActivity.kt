package com.example.sonyrx10m3remote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModelProvider
import com.example.sonyrx10m3remote.camera.CameraControllerProvider
import com.example.sonyrx10m3remote.gallery.GalleryScreen
import com.example.sonyrx10m3remote.gallery.GalleryViewModel
import com.example.sonyrx10m3remote.gallery.GalleryViewModelFactory
import com.example.sonyrx10m3remote.media.MediaManagerProvider
import com.example.sonyrx10m3remote.ui.theme.RX10M3RemoteTheme
import kotlinx.coroutines.launch

class GalleryActivity : ComponentActivity() {
    private lateinit var viewModel: GalleryViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val mediaManager = MediaManagerProvider.instance
        val cameraController = CameraControllerProvider.instance
        val cameraId = intent.getStringExtra("camera_id")

        val factory = GalleryViewModelFactory(applicationContext, mediaManager, cameraController, cameraId)
        viewModel = ViewModelProvider(this, factory)[GalleryViewModel::class.java]

        lifecycleScope.launch {
            viewModel.onGalleryOpened()
        }

        setContent {
            RX10M3RemoteTheme {
                GalleryScreen(viewModel = viewModel,
                    cameraController = CameraControllerProvider.instance,
                    mediaManager = MediaManagerProvider.instance,
                    cameraId = cameraId,
                    onDownloadSelected = { viewModel.downloadChosenImages() }
                )
            }
        }
    }

    override fun onStop() {
        super.onStop()
        if (isFinishing) {
            viewModel.onGalleryClosed()
        }
    }
}