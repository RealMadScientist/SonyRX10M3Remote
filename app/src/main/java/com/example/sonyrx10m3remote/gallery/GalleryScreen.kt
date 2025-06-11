package com.example.sonyrx10m3remote.gallery

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.sonyrx10m3remote.camera.CameraController

@Composable
fun GalleryScreen(
    viewModel: GalleryViewModel = viewModel(),
    cameraController: CameraController?,
    modifier: Modifier = Modifier
) {
    val cameraAvailable = cameraController != null

    val images by viewModel.sessionImages.collectAsState()
    val cameraSdViewType by viewModel.cameraSdViewType.collectAsState()
    val cameraSdImages by viewModel.cameraSdImages.collectAsState()
    val cameraSdVideos by viewModel.cameraSdVideos.collectAsState()
    val downloadedImages by viewModel.downloadedImages.collectAsState()
    val selected by viewModel.selectedImage.collectAsState()
    val mode by viewModel.mode.collectAsState()

    val shownImages = when (mode) {
        GalleryMode.SESSION -> images
        GalleryMode.CAMERA_SD -> when (cameraSdViewType) {
            CameraSdViewType.IMAGES -> cameraSdImages
            CameraSdViewType.VIDEOS -> cameraSdVideos
        }
        GalleryMode.DOWNLOADED -> downloadedImages
    }

    // Switch camera mode when this screen is shown/hidden
    LaunchedEffect(Unit) {
        cameraController?.let { viewModel.onGalleryOpened(it) }
    }

    DisposableEffect(Unit) {
        onDispose {
            cameraController?.let { viewModel.onGalleryClosed(it) }
        }
    }

    Scaffold(
        bottomBar = {
            Column {
                if (mode == GalleryMode.CAMERA_SD) {
                    CameraSdTypeToggle(
                        selectedType = viewModel.cameraSdViewType.collectAsState().value,
                        onSelectType = viewModel::setCameraSdViewType
                    )
                }
                GalleryBottomBar(
                    currentMode = mode,
                    onModeSelected = { newMode ->
                        if (newMode == GalleryMode.CAMERA_SD && !cameraAvailable) {
                            // Maybe show a Toast or snackbar here to notify user
                            return@GalleryBottomBar
                        }
                        viewModel.setMode(newMode, cameraController)
                    },
                    cameraAvailable = cameraAvailable
                )
            }
        }
    ) { paddingValues ->
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                shownImages.isEmpty() -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Loading... this may take a while.")
                    }
                }
                selected == null -> {
                    ImageGrid(images = shownImages, onImageClick = viewModel::selectImage)
                }
                else -> {
                    FullscreenImagePager(
                        images = shownImages,
                        startImage = selected!!,
                        onClose = { viewModel.selectImage(null) },
                    )
                }
            }
        }
    }
}

@Composable
fun CameraSdTypeToggle(
    selectedType: CameraSdViewType,
    onSelectType: (CameraSdViewType) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        SegmentedButton(
            text = "Images",
            selected = selectedType == CameraSdViewType.IMAGES,
            onClick = { onSelectType(CameraSdViewType.IMAGES) }
        )
        Spacer(modifier = Modifier.width(8.dp))
        SegmentedButton(
            text = "Videos",
            selected = selectedType == CameraSdViewType.VIDEOS,
            onClick = { onSelectType(CameraSdViewType.VIDEOS) }
        )
    }
}

@Composable
fun SegmentedButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primary else Color.LightGray,
            contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else Color.Black
        ),
        shape = MaterialTheme.shapes.small,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(text)
    }
}

@Composable
fun GalleryBottomBar(
    currentMode: GalleryMode,
    onModeSelected: (GalleryMode) -> Unit,
    cameraAvailable: Boolean
) {
    val primaryColor = MaterialTheme.colorScheme.primary

    NavigationBar {
        NavigationBarItem(
            icon = { Icon(Icons.Default.PhotoLibrary, contentDescription = "Session") },
            label = { Text("Session") },
            selected = currentMode == GalleryMode.SESSION,
            onClick = { onModeSelected(GalleryMode.SESSION) },
            colors = NavigationBarItemDefaults.colors(
                indicatorColor = primaryColor
            )
        )
        NavigationBarItem(
            icon = { Icon(Icons.Default.Storage, contentDescription = "Camera SD") },
            label = { Text("Camera SD") },
            selected = currentMode == GalleryMode.CAMERA_SD,
            onClick = { if (cameraAvailable) onModeSelected(GalleryMode.CAMERA_SD) },
            enabled = cameraAvailable,
            colors = NavigationBarItemDefaults.colors(
                indicatorColor = if (cameraAvailable) primaryColor else Color.Gray
            )
        )
        NavigationBarItem(
            icon = { Icon(Icons.Default.Download, contentDescription = "Downloaded") },
            label = { Text("Downloaded") },
            selected = currentMode == GalleryMode.DOWNLOADED,
            onClick = { onModeSelected(GalleryMode.DOWNLOADED) },
            colors = NavigationBarItemDefaults.colors(
                indicatorColor = primaryColor
            )
        )
    }
}

@Composable
fun ImageGrid(
    images: List<CapturedImage>,
    onImageClick: (CapturedImage) -> Unit
) {
    val gridState = rememberLazyGridState()

    // Scroll to bottom (last item) when images list changes
    LaunchedEffect(images.size) {
        if (images.isNotEmpty()) {
            gridState.scrollToItem(images.size - 1)
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 100.dp),
        contentPadding = PaddingValues(4.dp),
        modifier = Modifier.fillMaxSize(),
        state = gridState
    ) {
        items(images, key = { it.id }) { image ->
            val model = image.localUri ?: image.thumbnailUrl ?: image.remoteUrl
            Box(
                modifier = Modifier
                    .padding(4.dp)
                    .aspectRatio(1f)
                    .clickable { onImageClick(image) }
            ) {
                if (model != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(model)
                            .crossfade(true)
                            .build(),
                        contentDescription = "Thumbnail",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Gray),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("No preview", color = Color.White)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FullscreenImagePager(
    images: List<CapturedImage>,
    startImage: CapturedImage,
    onClose: () -> Unit
) {
    val startIndex = images.indexOf(startImage).coerceAtLeast(0)

    val pagerState = rememberPagerState(
        initialPage = startIndex,
        pageCount = { images.size }
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val image = images[page]
            val model = image.localUri ?: image.remoteUrl ?: image.thumbnailUrl
            if (model != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(model)
                        .crossfade(true)
                        .build(),
                    contentDescription = "Preview Image",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable { onClose() }
                )
            }
        }

        IconButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
        ) {
            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
        }
    }
}
