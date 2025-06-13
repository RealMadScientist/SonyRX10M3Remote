package com.example.sonyrx10m3remote.gallery

import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
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
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.sonyrx10m3remote.camera.CameraController
import com.example.sonyrx10m3remote.data.CapturedImage
import com.example.sonyrx10m3remote.media.MediaManager

@Composable
fun GalleryScreen(
    viewModel: GalleryViewModel = viewModel(),
    cameraController: CameraController?,
    mediaManager: MediaManager?,
    cameraId: String?,
    modifier: Modifier = Modifier,
    onDownloadSelected: () -> Unit
) {
    val cameraAvailable = cameraController != null

    val images by viewModel.sessionImages.collectAsState()
    val cameraSdViewType by viewModel.cameraSdViewType.collectAsState()
    val cameraSdImages by viewModel.cameraSdImages.collectAsState()
    val cameraSdVideos by viewModel.cameraSdVideos.collectAsState()
    val sessionLoading by viewModel.sessionLoading.collectAsState()
    val cameraSdLoading by viewModel.cameraSdLoading.collectAsState()
    val downloadedLoading by viewModel.downloadedLoading.collectAsState()
    val downloadedImages by viewModel.downloadedImages.collectAsState()
    val selected by viewModel.selectedImage.collectAsState()
    val mode by viewModel.mode.collectAsState()
    val thumbnailCache by mediaManager?.thumbnailCache?.collectAsState() ?: remember { mutableStateOf(emptyMap()) }

    val chosenImages by viewModel.chosenImages.collectAsState()
    val capturedImages by viewModel.capturedImages.collectAsState()
    val showPopup by viewModel.showIntervalPopup.collectAsState()
    val isDownloading by viewModel.isDownloading.collectAsState()
    val isInSelectionMode by viewModel.isInSelectionMode.collectAsState()

    val shownImages = when (mode) {
        GalleryMode.SESSION -> images
        GalleryMode.CAMERA_SD -> when (cameraSdViewType) {
            CameraSdViewType.IMAGES -> cameraSdImages
            CameraSdViewType.VIDEOS -> cameraSdVideos
        }
        GalleryMode.DOWNLOADED -> downloadedImages
    }

    val gridState = rememberLazyGridState()
    val lastScrollIndex = remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(Unit) {
        viewModel.onGalleryOpened()
    }

    // Switch camera mode when this screen is shown/hidden
    LaunchedEffect(lastScrollIndex.value) {
        val index = lastScrollIndex.value
        if (index != null) {
            gridState.animateScrollToItem(index)
            lastScrollIndex.value = null // Reset after restore
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.onGalleryClosed()
        }
    }

    Scaffold(
        bottomBar = {
            Column {
                if (mode == GalleryMode.CAMERA_SD) {
                    if (isInSelectionMode) {
                        // 🔁 Replace toggle with selection buttons
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            Button(
                                onClick = { viewModel.exitSelectionMode() },
                                modifier = Modifier.weight(1f).padding(end = 4.dp)
                            ) {
                                Text("Cancel")
                            }

                            Button(
                                onClick = onDownloadSelected,
                                modifier = Modifier.weight(1f).padding(start = 4.dp),
                                enabled = chosenImages.isNotEmpty()
                            ) {
                                Text("Download (${chosenImages.size})")
                            }
                        }
                    } else {
                        // 📷 Show Images/Videos toggle when not selecting
                        CameraSdTypeToggle(
                            selectedType = cameraSdViewType,
                            onSelectType = viewModel::setCameraSdViewType
                        )
                    }
                }
                GalleryBottomBar(
                    currentMode = mode,
                    onModeSelected = { newMode ->
                        if (newMode == GalleryMode.CAMERA_SD && !cameraAvailable) {
                            // Maybe show a Toast or snackbar here to notify user
                            return@GalleryBottomBar
                        }
                        viewModel.setMode(newMode)
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
            when (mode) {
                GalleryMode.CAMERA_SD -> {
                    val isImagesView = cameraSdViewType == CameraSdViewType.IMAGES
                    val items = if (isImagesView) cameraSdImages else cameraSdVideos

                    if (selected != null) {
                        FullscreenImagePager(
                            images = items,
                            startImage = selected!!,
                            onClose = { viewModel.selectImage(null) }
                        )
                    } else {
                        when {
                            cameraSdLoading && items.isEmpty() -> {
                                // full-screen loading
                                Column(
                                    modifier = Modifier.align(Alignment.Center),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    CircularProgressIndicator()
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text("Loading... this may take a while.")
                                }
                            }
                            items.isNotEmpty() -> {
                                Box {
                                    ImageGrid(
                                        images = items,
                                        thumbnailCache = thumbnailCache,
                                        gridState = gridState,
                                        onImageClick = { image ->
                                            if (!isInSelectionMode) {
                                                lastScrollIndex.value = gridState.firstVisibleItemIndex
                                                viewModel.selectImage(image)
                                            }
                                        },
                                        onSelectImage = { image, selected ->
                                            if (isInSelectionMode) {
                                                viewModel.updateChosenImage(image, selected)
                                            }
                                        },
                                        onLongClick = { image ->
                                            if (!isInSelectionMode) {
                                                viewModel.enterSelectionMode()
                                            }
                                        },
                                        selectedImages = chosenImages,
                                        selectionEnabled = isInSelectionMode,
                                        isDownloading = isDownloading
                                    )
                                    if (cameraSdLoading) {
                                        // subtle top indicator
                                        LinearProgressIndicator(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(4.dp)
                                                .align(Alignment.TopCenter)
                                        )
                                    }
                                }
                            }
                            !cameraSdLoading && items.isEmpty() -> {
                                // no items found
                                Column(
                                    modifier = Modifier.align(Alignment.Center),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text("No images found on camera SD.")
                                }
                            }
                        }
                    }
                }
                GalleryMode.SESSION -> {
                    when {
                        sessionLoading -> {
                            Column(
                                modifier = Modifier.align(Alignment.Center),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                CircularProgressIndicator()
                                Spacer(modifier = Modifier.height(16.dp))
                                Text("Loading session images...")
                            }
                        }
                        shownImages.isEmpty() -> {
                            Column(
                                modifier = Modifier.align(Alignment.Center),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text("No session images found.")
                            }
                        }
                        selected == null -> {
                            ImageGrid(images = shownImages,
                                thumbnailCache = thumbnailCache,
                                gridState = gridState,
                                onImageClick = { image ->
                                    // Save current scroll before fullscreen
                                    lastScrollIndex.value = gridState.firstVisibleItemIndex
                                    viewModel.selectImage(image)
                                }
                            )
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
                GalleryMode.DOWNLOADED -> {
                    when {
                        downloadedLoading -> {
                            Column(
                                modifier = Modifier.align(Alignment.Center),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                CircularProgressIndicator()
                                Spacer(modifier = Modifier.height(16.dp))
                                Text("Loading downloaded images...")
                            }
                        }
                        shownImages.isEmpty() -> {
                            Column(
                                modifier = Modifier.align(Alignment.Center),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text("No downloaded images found.")
                            }
                        }
                        selected == null -> {
                            ImageGrid(images = shownImages,
                                thumbnailCache = thumbnailCache,
                                gridState = gridState,
                                onImageClick = { image ->
                                    // Save current scroll before fullscreen
                                    lastScrollIndex.value = gridState.firstVisibleItemIndex
                                    viewModel.selectImage(image)
                                }
                            )
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
    }

    if (showPopup) {
        IntervalCapturedImagesPopup(
            images = capturedImages,
            selectedImages = chosenImages,
            onDismiss = { viewModel.dismissIntervalPopup() },
            onConfirmDownload = {
                onDownloadSelected()
                viewModel.dismissIntervalPopup()
            },
            onSelectImage = { image, selected ->
                viewModel.updateChosenImage(image, selected)
            },
            isDownloading = isDownloading
        )
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
            .padding(8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        Button(
            onClick = { onSelectType(CameraSdViewType.IMAGES) },
            modifier = Modifier.weight(1f).padding(end = 4.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (selectedType == CameraSdViewType.IMAGES)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.surfaceVariant,
                contentColor = if (selectedType == CameraSdViewType.IMAGES)
                    MaterialTheme.colorScheme.onPrimary
                else
                    MaterialTheme.colorScheme.onSurfaceVariant
            )
        ) {
            Text("Images")
        }

        Button(
            onClick = { onSelectType(CameraSdViewType.VIDEOS) },
            modifier = Modifier.weight(1f).padding(start = 4.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (selectedType == CameraSdViewType.VIDEOS)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.surfaceVariant,
                contentColor = if (selectedType == CameraSdViewType.VIDEOS)
                    MaterialTheme.colorScheme.onPrimary
                else
                    MaterialTheme.colorScheme.onSurfaceVariant
            )
        ) {
            Text("Videos")
        }
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ImageGrid(
    images: List<CapturedImage>,
    thumbnailCache: Map<String, Uri>,
    gridState: LazyGridState,
    onImageClick: (CapturedImage) -> Unit,
    onLongClick: (CapturedImage) -> Unit = {}, // default no-op
    selectedImages: Set<CapturedImage> = emptySet(),
    onSelectImage: (CapturedImage, Boolean) -> Unit = { _, _ -> },
    isDownloading: Boolean = false,
    selectionEnabled: Boolean = false
) {
    LaunchedEffect(images.size) {
        if (images.isNotEmpty() && gridState.firstVisibleItemIndex == 0) {
            gridState.scrollToItem(images.size - 1)
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 100.dp),
        contentPadding = PaddingValues(4.dp),
        modifier = Modifier.fillMaxSize(),
        state = gridState
    ) {
        items(images, key = { it.uri }) { image ->
            val model = thumbnailCache[image.uri] ?: image.localUri ?: image.thumbnailUrl ?: image.remoteUrl
            val isSelected = selectedImages.contains(image)

            Box(
                modifier = Modifier
                    .padding(4.dp)
                    .aspectRatio(1f)
                    .combinedClickable(
                        onClick = {
                            if (isDownloading) return@combinedClickable // ignore clicks during download

                            if (selectionEnabled) {
                                // Toggle selection state
                                onSelectImage(image, !isSelected)
                            } else {
                                // Normal image click
                                onImageClick(image)
                            }
                        },
                        onLongClick = {
                            if (isDownloading) return@combinedClickable
                            onLongClick(image)
                        }
                    )
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

                // Overlay selection check if selected
                if (isSelected) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.5f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Selected",
                            tint = Color.White
                        )
                    }
                }

                if (isDownloading) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(Color.Black.copy(alpha = 0.5f)),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
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
            val model = image.remoteUrl ?: image.localUri ?: image.thumbnailUrl
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

@Composable
fun IntervalCapturedImagesPopup(
    images: List<CapturedImage>,
    selectedImages: Set<CapturedImage>,
    onDismiss: () -> Unit,
    onConfirmDownload: () -> Unit,
    onSelectImage: (CapturedImage, Boolean) -> Unit,
    isDownloading: Boolean
) {
    Dialog(onDismissRequest = { if (!isDownloading) onDismiss() }) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color.Black,
                modifier = Modifier.align(Alignment.Center)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Captured Images", style = MaterialTheme.typography.titleMedium, color = Color.White)
                    Spacer(Modifier.height(8.dp))
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.height(300.dp)
                    ) {
                        items(images, key = { it.uri }) { image ->
                            val model = image.localUri ?: image.thumbnailUrl ?: image.remoteUrl
                            Box(
                                modifier = Modifier
                                    .padding(4.dp)
                                    .aspectRatio(1f)
                                    .clickable(enabled = !isDownloading) {
                                        val isSelected = selectedImages.contains(image)
                                        onSelectImage(image, !isSelected)
                                    }
                            ) {
                                AsyncImage(
                                    model = model,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                                if (selectedImages.contains(image)) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(Color.Black.copy(alpha = 0.5f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = "Selected",
                                            tint = Color.White
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(
                        horizontalArrangement = Arrangement.End,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        TextButton(
                            onClick = onDismiss,
                            enabled = !isDownloading
                        ) { Text("Cancel", color = Color.White) }

                        Spacer(Modifier.width(8.dp))

                        Button(
                            onClick = onConfirmDownload,
                            enabled = !isDownloading
                        ) { Text("Download Selected", color = Color.White) }
                    }
                }
            }

            if (isDownloading) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(Color.Black.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}