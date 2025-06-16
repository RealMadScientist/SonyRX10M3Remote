package com.example.sonyrx10m3remote.gallery

import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.sonyrx10m3remote.camera.CameraController
import com.example.sonyrx10m3remote.data.CapturedImage
import com.example.sonyrx10m3remote.gallery.GalleryViewModel
import com.example.sonyrx10m3remote.media.MediaManager
import com.example.sonyrx10m3remote.R
import java.util.*
import kotlinx.coroutines.delay

data class ScrollPosition(val index: Int = 0, val offset: Int = 0)

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
    val sessionLoading by viewModel.sessionLoading.collectAsState()
    val cameraSdLoading by viewModel.cameraSdLoading.collectAsState()
    val downloadedLoading by viewModel.downloadedLoading.collectAsState()
    val downloadedImages by viewModel.downloadedImages.collectAsState()
    val selected by viewModel.selectedImage.collectAsState()
    val mode by viewModel.mode.collectAsState()
    val thumbnailCache by mediaManager?.thumbnailCache?.collectAsState() ?: remember { mutableStateOf(emptyMap()) }

    val cachedDates by viewModel.cachedDates.collectAsState()
    val datePreviews by viewModel.datePreviews.collectAsState()
    val selectedDate by viewModel.selectedDate.collectAsState()
    val contentForDate by viewModel.contentForDate.collectAsState()

    val chosenImages by viewModel.chosenImages.collectAsState()
    val capturedImages by viewModel.capturedImages.collectAsState()
    val showPopup by viewModel.showIntervalPopup.collectAsState()
    val isDownloading by viewModel.isDownloading.collectAsState()
    val isInSelectionMode by viewModel.isInSelectionMode.collectAsState()
    val loadingDates by viewModel.loadingDates.collectAsState()
    val isSelectedDateLoading by viewModel.isSelectedDateLoading.collectAsState()

    var ungroupedScrollPosition by remember { mutableStateOf(ScrollPosition()) }
    var groupedScrollPosition by remember { mutableStateOf(ScrollPosition()) }
    val ungroupedGridState = rememberLazyGridState()
    val groupedListState = rememberLazyListState()

    val shownImages = when (mode) {
        GalleryMode.SESSION -> images
        GalleryMode.CAMERA_SD -> contentForDate
        GalleryMode.DOWNLOADED -> downloadedImages
    }

    val gridState = rememberLazyGridState()
    val lastScrollIndex = remember { mutableStateOf<Int?>(null) }

    // Switch camera mode when this screen is shown/hidden
    LaunchedEffect(lastScrollIndex.value) {
        val index = lastScrollIndex.value
        if (index != null) {
            gridState.animateScrollToItem(index)
            lastScrollIndex.value = null // Reset after restore
        }
    }

    LaunchedEffect(cachedDates, mode) {
        if (mode == GalleryMode.CAMERA_SD) {
            if (cachedDates.isNotEmpty()) {
                groupedListState.scrollToItem(((cachedDates.size * 2) - 1).coerceAtLeast(0))
            }
        }
    }

    LaunchedEffect(ungroupedGridState.firstVisibleItemIndex, ungroupedGridState.firstVisibleItemScrollOffset) {
        ungroupedScrollPosition = ScrollPosition(ungroupedGridState.firstVisibleItemIndex, ungroupedGridState.firstVisibleItemScrollOffset)
    }

    LaunchedEffect(groupedListState.firstVisibleItemIndex, groupedListState.firstVisibleItemScrollOffset) {
        groupedScrollPosition = ScrollPosition(groupedListState.firstVisibleItemIndex, groupedListState.firstVisibleItemScrollOffset)
    }

    BackHandler(enabled = selected != null || selectedDate != null) {
        when {
            selected != null -> {
                viewModel.selectImage(null)   // close fullscreen image -> go back to image grid
            }
            selectedDate != null -> {
                viewModel.selectDate(null)    // close date group -> go back to grouped image list
            }
        }
    }

    Scaffold(
        bottomBar = {
            if (selected == null) {
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
        }
    ) { paddingValues ->
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when (mode) {
                GalleryMode.CAMERA_SD -> {
                    val date = selectedDate
                    val filteredContent = contentForDate

                    when {
                        selected != null -> {
                            // Fullscreen image viewer for a selected image
                            FullscreenImagePager(
                                images = filteredContent,
                                startImage = selected!!,
                                onClose = { viewModel.selectImage(null) }
                            )
                        }

                        cameraSdLoading -> {
                            Column(
                                modifier = Modifier.align(Alignment.Center),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                CircularProgressIndicator()
                                Spacer(modifier = Modifier.height(16.dp))
                                Text("Loading...this may take a while.")
                            }
                        }

                        selectedDate != null -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(top = 8.dp),
                            ) {
                                Text(
                                    text = "Viewing: ${formatDateKey(date!!)}",
                                    modifier = Modifier
                                        .align(Alignment.CenterHorizontally)
                                        .padding(bottom = 8.dp),
                                    style = MaterialTheme.typography.bodyMedium
                                )

                                if (filteredContent.isEmpty()) {
                                    Column(
                                        modifier = Modifier.fillMaxSize().wrapContentSize(Alignment.Center),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        if (isSelectedDateLoading) {
                                            CircularProgressIndicator()
                                            Spacer(modifier = Modifier.height(16.dp))
                                            Text("Loading images for $selectedDate...")
                                        } else {
                                            Text("No images found for $selectedDate")
                                        }
                                    }
                                } else {
                                    ImageGrid(
                                        images = filteredContent,
                                        thumbnailCache = thumbnailCache,
                                        gridState = gridState,
                                        onImageClick = { image ->
                                            if (!isInSelectionMode) {
                                                viewModel.selectImage(image)
                                            }
                                        },
                                        onLongClick = {
                                            if (!isInSelectionMode) viewModel.enterSelectionMode()
                                        },
                                        selectedImages = chosenImages,
                                        isDownloading = isDownloading,
                                        selectionEnabled = isInSelectionMode,
                                        onSelectImage = { image, selected ->
                                            viewModel.updateChosenImage(image, selected)
                                        },
                                        requestThumbnail = { viewModel.requestThumbnailIfNeeded(it) },
                                        onPrefetchNearby = { viewModel.prefetchThumbnailsIfNeeded(it) }
                                    )
                                }
                            }
                        }

                        else -> {
                            // No date selected, show list of date groups or a "no images" message if no dates
                            if (cachedDates.isEmpty()) {
                                Column(
                                    modifier = Modifier.align(Alignment.Center),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text("No images found on camera SD.")
                                }
                            } else {
                                GroupedImageGrid(
                                    dates = cachedDates.asReversed(),
                                    datePreviews = datePreviews,
                                    thumbnailCache = thumbnailCache,
                                    listState = groupedListState,
                                    selectedImages = chosenImages,
                                    isDownloading = isDownloading,
                                    selectionEnabled = isInSelectionMode,
                                    onImageClick = { date ->
                                        if (!isInSelectionMode) {
                                            viewModel.selectDate(date)
                                        }
                                    },
                                    onLongClick = {
                                        if (!isInSelectionMode) viewModel.enterSelectionMode()
                                    },
                                    onSelectImage = { image, selected ->
                                        viewModel.updateChosenImage(image, selected)
                                    },
                                    requestThumbnail = { viewModel.requestThumbnailIfNeeded(it) },
                                    onPrefetchNearby = { viewModel.prefetchThumbnailsIfNeeded(it) },
                                    onDateClick = { date ->
                                        viewModel.selectDate(date) // Sets selectedDate and triggers loading
                                    },
                                    onDateGroupVisible = { viewModel.loadPreviewMetadataIfCached(it) },
                                    loadingDates = loadingDates
                                )
                            }
                        }
                    }

                    if (isDownloading) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .align(Alignment.TopCenter)
                        )
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
                            ImageGrid(
                                images = shownImages,
                                thumbnailCache = thumbnailCache,
                                gridState = ungroupedGridState,
                                onImageClick = { image ->
                                    viewModel.selectImage(image)
                                }
                            )
                        }
                        else -> {
                            FullscreenImagePager(
                                images = shownImages,
                                startImage = selected!!,
                                onClose = { viewModel.selectImage(null) }
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
    onLongClick: (CapturedImage) -> Unit = {},
    selectedImages: Set<CapturedImage> = emptySet(),
    onSelectImage: (CapturedImage, Boolean) -> Unit = { _, _ -> },
    isDownloading: Boolean = false,
    selectionEnabled: Boolean = false,
    requestThumbnail: ((CapturedImage) -> Unit)? = null,
    onPrefetchNearby: ((List<CapturedImage>) -> Unit)? = null
) {
    val lastPrefetchIndex = remember { mutableStateOf(0) }
    val lastPrefetchOffset = remember { mutableStateOf(0) }
    val tileHeightPx = with(LocalDensity.current) { 100.dp.toPx().toInt() }

    val toggledDuringDrag = remember { mutableSetOf<CapturedImage>() }
    val lastToggleTimes = remember { mutableMapOf<CapturedImage, Long>() }
    val toggleCooldownMs = 300L

    fun canToggle(image: CapturedImage): Boolean {
        val now = System.currentTimeMillis()
        val lastToggle = lastToggleTimes[image] ?: 0L
        return if (now - lastToggle > toggleCooldownMs) {
            lastToggleTimes[image] = now
            true
        } else {
            false
        }
    }

    LaunchedEffect(images.size) {
        if (images.isNotEmpty() && gridState.firstVisibleItemIndex == 0) {
            gridState.scrollToItem(images.size - 1)
        }
    }

    LaunchedEffect(gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset) {
        val indexDelta = kotlin.math.abs(gridState.firstVisibleItemIndex - lastPrefetchIndex.value)
        val offsetDelta = kotlin.math.abs(gridState.firstVisibleItemScrollOffset - lastPrefetchOffset.value)

        if (indexDelta > 0 || offsetDelta > tileHeightPx) {
            lastPrefetchIndex.value = gridState.firstVisibleItemIndex
            lastPrefetchOffset.value = gridState.firstVisibleItemScrollOffset

            val visible = gridState.layoutInfo.visibleItemsInfo.map { it.index }
            if (visible.isNotEmpty()) {
                val min = visible.minOrNull() ?: 0
                val max = visible.maxOrNull() ?: 0
                val prefetchRange = (min - 10).coerceAtLeast(0)..(max + 10).coerceAtMost(images.lastIndex)
                val toPrefetch = images.slice(prefetchRange)
                onPrefetchNearby?.invoke(toPrefetch)
            }
        }
    }

    Box(
        modifier = if (selectionEnabled) {
            Modifier
                .fillMaxSize()
                .pointerInput(images, selectedImages) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val pointer = event.changes.firstOrNull() ?: continue
                            val pos = pointer.position

                            val hitItemInfo = gridState.layoutInfo.visibleItemsInfo.find { info ->
                                val xInBounds = pos.x >= info.offset.x && pos.x <= info.offset.x + info.size.width
                                val yInBounds = pos.y >= info.offset.y && pos.y <= info.offset.y + info.size.height
                                xInBounds && yInBounds
                            }

                            if (hitItemInfo != null) {
                                val image = images.getOrNull(hitItemInfo.index)
                                if (image != null && !toggledDuringDrag.contains(image) && canToggle(image)) {
                                    val currentlySelected = selectedImages.contains(image)
                                    onSelectImage(image, !currentlySelected)
                                    toggledDuringDrag.add(image)
                                }
                            }

                            event.changes.forEach { change ->
                                if (change.changedToUp()) {
                                    toggledDuringDrag.clear()
                                }
                                change.consume()
                            }
                        }
                    }
                }
        } else Modifier.fillMaxSize()
    ) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 100.dp),
            contentPadding = PaddingValues(4.dp),
            modifier = Modifier.fillMaxSize(),
            state = gridState
        ) {
            items(images, key = { it.uri }) { image ->
                val model = thumbnailCache[image.uri] ?: image.localUri ?: image.thumbnailUrl ?: image.remoteUrl
                val isSelected = selectedImages.contains(image)

                if (requestThumbnail != null) {
                    LaunchedEffect(image.uri) {
                        requestThumbnail(image)
                    }
                }

                Box(
                    modifier = Modifier
                        .padding(4.dp)
                        .aspectRatio(1f)
                        .combinedClickable(
                            onClick = {
                                if (isDownloading) return@combinedClickable
                                if (selectionEnabled) {
                                    if (canToggle(image)) {
                                        onSelectImage(image, !isSelected)
                                    }
                                } else {
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
                            modifier = Modifier.fillMaxSize(),
                            placeholder = painterResource(R.drawable.placeholder_thumbnail),
                            error = painterResource(R.drawable.placeholder_thumbnail),
                            fallback = painterResource(R.drawable.placeholder_thumbnail)
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
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GroupedImageGrid(
    dates: List<String>,                     // full flat list still needed for grouping/sorting keys
    datePreviews: Map<String, List<CapturedImage>>,  // <-- new previews map keyed by date
    thumbnailCache: Map<String, Uri>,
    listState: LazyListState,
    selectedImages: Set<CapturedImage>,
    isDownloading: Boolean,
    selectionEnabled: Boolean,
    onImageClick: (String) -> Unit,  // <-- change here
    onLongClick: (CapturedImage) -> Unit = {},
    onSelectImage: (CapturedImage, Boolean) -> Unit = { _, _ -> },
    requestThumbnail: ((CapturedImage) -> Unit)? = null,
    onPrefetchNearby: ((List<CapturedImage>) -> Unit)? = null,
    onDateClick: (String) -> Unit,
    onDateGroupVisible: (String) -> Unit,
    loadingDates: Set<String>
) {
    val triggeredPreviews = remember { mutableStateListOf<String>() }

    // Observe visible items outside LazyColumn, call onDateGroupVisible as needed
    val visibleIndices by remember {
        derivedStateOf {
            listState.layoutInfo.visibleItemsInfo.map { it.index }
        }
    }

    LaunchedEffect(visibleIndices) {
        // Assuming each header is at index = date index * 2 (header + content alternating)
        dates.forEachIndexed { index, dateStr ->
            val headerIndex = index * 2
            if (visibleIndices.contains(headerIndex)) {
                onDateGroupVisible(dateStr)
            }
        }
    }

    LazyColumn(modifier = Modifier.fillMaxSize(), state = listState) {
        dates.forEach { dateStr ->
            val isLoading = loadingDates.contains(dateStr)
            val previewImages = datePreviews[dateStr] ?: emptyList()

            item {
                Text(
                    text = formatDateKey(dateStr),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.LightGray.copy(alpha = 0.3f))
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .clickable { onDateClick(dateStr) }
                )
            }

            item(key = "previews-$dateStr") {
                // Lazy thumbnail fetch trigger
                if (!triggeredPreviews.contains(dateStr)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onGloballyPositioned {
                                if (!triggeredPreviews.contains(dateStr)) {
                                    triggeredPreviews.add(dateStr)
                                    previewImages
                                        .take(4)
                                        .filter { it.localUri == null && it.thumbnailUrl != null }
                                        .forEach { image ->
                                            requestThumbnail?.invoke(image)
                                        }
                                }
                            }
                    ) {}
                }

                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(80.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        previewImages.forEach { image ->
                            val model = thumbnailCache[image.uri]
                                ?: image.localUri
                                ?: image.thumbnailUrl
                                ?: image.remoteUrl

                            val isSelected = selectedImages.contains(image)

                            Box(
                                modifier = Modifier
                                    .size(80.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .combinedClickable(
                                        onClick = {
                                            if (isDownloading) return@combinedClickable
                                            if (selectionEnabled) {
                                                onSelectImage(image, !isSelected)
                                            } else {
                                                Log.d(
                                                    "GroupedImageGrid",
                                                    "Clicked preview image: $image"
                                                )
                                                onDateClick(dateStr)
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
                                        model = model,
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
                ZoomableImage(
                    model = model,
                    modifier = Modifier.fillMaxSize(),
                    contentDescription = "Preview Image"
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
fun ZoomableImage(
    model: Any,
    modifier: Modifier = Modifier,
    contentDescription: String? = null
) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var rotationState by remember { mutableStateOf(0f) } // optional rotation support

    val maxScale = 5f
    val minScale = 1f

    Box(
        modifier = modifier
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val newScale = (scale * zoom).coerceIn(minScale, maxScale)
                    scale = newScale

                    // Only allow offset movement if zoomed in
                    if (newScale > 1f) {
                        offset += pan
                    } else {
                        offset = Offset.Zero
                    }
                }
            }
            .graphicsLayer(
                scaleX = scale,
                scaleY = scale,
                translationX = offset.x,
                translationY = offset.y
            )
    ) {
        AsyncImage(
            model = model,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        )
    }
}


@OptIn(ExperimentalFoundationApi::class)
@Composable
fun IntervalCapturedImagesPopup(
    images: List<CapturedImage>,
    selectedImages: Set<CapturedImage>,
    onDismiss: () -> Unit,
    onConfirmDownload: () -> Unit,
    onSelectImage: (CapturedImage, Boolean) -> Unit,
    isDownloading: Boolean
) {
    val gridState = rememberLazyGridState()
    val toggledDuringDrag = remember { mutableSetOf<CapturedImage>() }
    val lastToggleTimes = remember { mutableMapOf<CapturedImage, Long>() }
    val toggleCooldownMs = 300L

    fun canToggle(image: CapturedImage): Boolean {
        val now = System.currentTimeMillis()
        val lastToggle = lastToggleTimes[image] ?: 0L
        return if (now - lastToggle > toggleCooldownMs) {
            lastToggleTimes[image] = now
            true
        } else {
            false
        }
    }

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

                    Box(
                        modifier = Modifier
                            .height(300.dp)
                            .pointerInput(images, selectedImages) {
                                awaitPointerEventScope {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val pointer = event.changes.firstOrNull() ?: continue
                                        val pos = pointer.position

                                        val hitItemInfo = gridState.layoutInfo.visibleItemsInfo.find { info ->
                                            val xInBounds = pos.x >= info.offset.x && pos.x <= info.offset.x + info.size.width
                                            val yInBounds = pos.y >= info.offset.y && pos.y <= info.offset.y + info.size.height
                                            xInBounds && yInBounds
                                        }

                                        if (hitItemInfo != null) {
                                            val image = images.getOrNull(hitItemInfo.index)
                                            if (image != null && !toggledDuringDrag.contains(image) && canToggle(image)) {
                                                val currentlySelected = selectedImages.contains(image)
                                                onSelectImage(image, !currentlySelected)
                                                toggledDuringDrag.add(image)
                                            }
                                        }

                                        event.changes.forEach { change ->
                                            if (change.changedToUp()) {
                                                toggledDuringDrag.clear()
                                            }
                                            change.consume()
                                        }
                                    }
                                }
                            }
                    ) {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            state = gridState,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(images, key = { it.uri }) { image ->
                                val model = image.localUri ?: image.thumbnailUrl ?: image.remoteUrl
                                val isSelected = selectedImages.contains(image)

                                Box(
                                    modifier = Modifier
                                        .padding(4.dp)
                                        .aspectRatio(1f)
                                        .combinedClickable(
                                            onClick = {
                                                if (!isDownloading && canToggle(image)) {
                                                    onSelectImage(image, !isSelected)
                                                }
                                            },
                                            onLongClick = {}
                                        )
                                ) {
                                    AsyncImage(
                                        model = model,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
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