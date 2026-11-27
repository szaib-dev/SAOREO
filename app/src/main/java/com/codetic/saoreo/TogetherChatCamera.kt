package com.codetic.saoreo

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recording
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.camera.view.video.AudioConfig
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Observer
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private data class CameraRecent(val uri: Uri, val video: Boolean)

@Composable
internal fun TogetherChatCamera(close: () -> Unit, openPicker: () -> Unit, onPick: (List<Uri>) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val executor = remember(context) { ContextCompat.getMainExecutor(context) }
    val controller = remember(context) { LifecycleCameraController(context).apply {
        setEnabledUseCases(CameraController.IMAGE_CAPTURE)
        videoCaptureQualitySelector = QualitySelector.fromOrderedList(
            listOf(Quality.HD, Quality.SD), FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))
    } }
    val preview = remember(context) { PreviewView(context).apply {
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        scaleType = PreviewView.ScaleType.FILL_CENTER
    } }
    val currentPick by rememberUpdatedState(onPick)
    var attached by remember { mutableStateOf(false) }
    var front by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptyList<Uri>()) }
    var pending by remember { mutableStateOf(false) }
    var streaming by remember { mutableStateOf(false) }
    var previewAttempt by remember { mutableIntStateOf(0) }
    var videoMode by remember { mutableStateOf(false) }
    var recordRequest by remember { mutableStateOf<Boolean?>(null) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var recordingTime by remember { mutableLongStateOf(0L) }
    var error by remember { mutableStateOf<String?>(null) }
    var libraryVersion by remember { mutableIntStateOf(0) }
    val activity = androidx.activity.compose.LocalActivity.current as? TogetherActivity
    val permissions = remember {
        listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO) +
            if (Build.VERSION.SDK_INT >= 34) listOf(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) else emptyList()
    }
    fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    val library = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        activity?.showingExternalPicker = false
        libraryVersion++
        if (result.values.any { it }) expanded = true else openPicker()
    }
    fun showGallery() {
        if (permissions.any(::granted)) expanded = true
        else {
            activity?.showingExternalPicker = true
            library.launch(permissions.toTypedArray())
        }
    }
    fun toggleMedia(uri: Uri) {
        selected = if (uri in selected) selected - uri else if (selected.size < 20) selected + uri else selected
    }
    fun closeGallery() {
        expanded = false
        selected = emptyList()
    }
    val recent by produceState(emptyList<CameraRecent>(), libraryVersion) {
        if (permissions.any(::granted)) value = withContext(Dispatchers.IO) { cameraRecents(context) }
    }
    val cameraProvider = controller.initializationFuture
    DisposableEffect(controller, lifecycle, previewAttempt) {
        var alive = true
        attached = true
        val observer = Observer<PreviewView.StreamState> { streaming = it == PreviewView.StreamState.STREAMING }
        preview.previewStreamState.observe(lifecycle, observer)
        preview.controller = controller
        cameraProvider.addListener({
            if (alive) try {
                cameraProvider.get()
                controller.bindToLifecycle(lifecycle)
            } catch (_: Exception) { error = "Camera preview is unavailable." }
        }, executor)
        onDispose {
            alive = false
            attached = false
            recording?.close()
            preview.previewStreamState.removeObserver(observer)
            preview.controller = null
            controller.unbind()
        }
    }
    LaunchedEffect(previewAttempt, streaming, expanded, videoMode) {
        if (!streaming && !expanded) {
            delay(8_000)
            recordRequest = null
            error = "The camera preview couldn’t start."
        }
    }
    fun useVideo(enabled: Boolean): Boolean {
        if (videoMode == enabled) return true
        return try {
            streaming = false
            preview.controller = null
            controller.setEnabledUseCases(if (enabled) CameraController.VIDEO_CAPTURE else CameraController.IMAGE_CAPTURE)
            videoMode = enabled
            preview.controller = controller
            true
        } catch (_: Exception) {
            runCatching {
                controller.setEnabledUseCases(CameraController.IMAGE_CAPTURE)
                videoMode = false
                preview.controller = controller
            }
            error = "This camera couldn’t switch capture modes."
            false
        }
    }
    fun photo() {
        if (pending || recording != null || recordRequest != null) return
        if (videoMode) { useVideo(false); return }
        if (!streaming) return
        val (file, uri) = chatDraftFile(context, ".jpg")
        pending = true
        try {
            controller.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), executor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        pending = false
                        if (attached) currentPick(listOf(uri)) else file.delete()
                    }
                    override fun onError(exception: ImageCaptureException) {
                        file.delete()
                        pending = false
                        error = "Couldn’t take a photo."
                    }
                })
        } catch (_: Exception) {
            file.delete()
            pending = false
            error = "Wait for the camera preview to start."
        }
    }
    fun record(withAudio: Boolean) {
        if (pending || recording != null || !streaming) return
        val (file, uri) = chatDraftFile(context, ".mp4")
        recordingTime = 0
        error = null
        try {
            val options = FileOutputOptions.Builder(file).setFileSizeLimit(24L * 1024 * 1024).build()
            recording = controller.startRecording(options, AudioConfig.create(withAudio), executor) { event ->
                if (event is VideoRecordEvent.Status) recordingTime = event.recordingStats.recordedDurationNanos / 1_000_000
                if (event is VideoRecordEvent.Finalize) {
                    recording = null
                    pending = false
                    val usable = !event.hasError() || event.error == VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED
                    if (attached && usable && file.length() > 0) currentPick(listOf(uri))
                    else {
                        file.delete()
                        if (attached) error = "Couldn’t record a video."
                    }
                }
            }
        } catch (_: Exception) {
            file.delete()
            error = "Wait for the camera preview to start."
        }
    }
    fun requestRecording(withAudio: Boolean) {
        if (useVideo(true)) recordRequest = withAudio
    }
    LaunchedEffect(streaming, recordRequest) {
        val withAudio = recordRequest
        if (streaming && withAudio != null) {
            recordRequest = null
            record(withAudio)
        }
    }
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        activity?.showingExternalPicker = false
        requestRecording(it)
    }
    fun video() {
        val active = recording
        if (active != null) { pending = true; active.stop() }
        else if (granted(Manifest.permission.RECORD_AUDIO)) requestRecording(true)
        else {
            activity?.showingExternalPicker = true
            microphone.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Dialog(onDismissRequest = { if (expanded) closeGallery() else close() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
            AndroidView(factory = { preview }, modifier = Modifier.fillMaxSize())
            if (!streaming && !expanded && error == null && recording == null)
                CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                CameraAction(if (expanded) "Back to camera" else "Close camera", R.drawable.ic_chat_down) {
                    if (expanded) closeGallery() else close()
                }
                Spacer(Modifier.weight(1f))
                if (recording != null) Surface(color = Color(0xffa93645), shape = CircleShape) {
                    Text(chatMediaDuration(recordingTime), Modifier.padding(horizontal = 14.dp, vertical = 7.dp), color = Color.White)
                }
                Spacer(Modifier.weight(1f))
                if (!expanded && recording == null) CameraAction("Switch camera", R.drawable.ic_camera_flip,
                    enabled = streaming && recordRequest == null) {
                    val selector = if (front) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
                    try { if (controller.hasCamera(selector)) { controller.cameraSelector = selector; front = !front } }
                    catch (_: Exception) { error = "Camera is still starting." }
                }
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Color.Black.copy(alpha = .72f)).padding(bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                error?.let {
                    Text(it, Modifier.padding(8.dp), color = Color.White)
                    if (!streaming) TextButton({ error = null; previewAttempt++ }) { Text("Retry camera") }
                }
                AnimatedVisibility(recording == null && !expanded) {
                    Column(Modifier.fillMaxWidth().pointerInput(Unit) {
                        var drag = 0f
                        detectVerticalDragGestures(onDragStart = { drag = 0f }, onDragEnd = {
                            if (drag < -30.dp.toPx()) showGallery()
                        }) { change, down -> change.consume(); drag += down }
                    }.padding(top = 8.dp, bottom = 3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(30.dp, 3.dp).background(Color.White.copy(alpha = .7f), CircleShape))
                        if (recent.isEmpty()) TextButton(::showGallery) { Text("Recent media", color = Color.White) }
                        else LazyRow(Modifier.fillMaxWidth(), contentPadding = PaddingValues(10.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(recent.take(16).size) { index ->
                                RecentCameraTile(recent[index], Modifier.size(57.dp), 0) {
                                    toggleMedia(it)
                                    expanded = true
                                }
                            }
                        }
                    }
                }
                if (!expanded) Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    CameraAction("Gallery", R.drawable.ic_profile_image, enabled = recording == null, action = ::showGallery)
                    FilledIconButton(::photo, Modifier.size(66.dp).border(2.dp, Color.White, CircleShape).padding(5.dp),
                        enabled = recording == null && !pending && recordRequest == null && streaming,
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White, contentColor = Color.Black)) {
                        Icon(painterResource(R.drawable.ic_chat_photo), "Take photo")
                    }
                    CameraAction(if (recording == null) "Record video" else "Stop recording",
                        if (recording == null) R.drawable.ic_camera else R.drawable.ic_chat_stop,
                        enabled = !pending && recordRequest == null && (recording != null || streaming), action = ::video)
                }
            }
            AnimatedVisibility(expanded, Modifier.fillMaxSize().padding(top = 68.dp),
                enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
            Surface(Modifier.fillMaxSize(), color = Color(0xff111315),
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)) {
                Column {
                    Row(Modifier.fillMaxWidth().pointerInput(Unit) {
                        var drag = 0f
                        detectVerticalDragGestures(onDragStart = { drag = 0f }, onDragEnd = {
                            if (drag > 30.dp.toPx()) closeGallery()
                        }) { change, amount -> change.consume(); drag += amount }
                    }.padding(horizontal = 14.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(if (selected.isEmpty()) "Recent media" else "${selected.size} selected",
                            Modifier.weight(1f), color = Color.White)
                        if (selected.isEmpty()) TextButton(openPicker) { Text("Browse albums") }
                        else Button({ currentPick(selected) }) { Text("Next") }
                    }
                    LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxSize(), contentPadding = PaddingValues(5.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(recent, key = { it.uri.toString() }) {
                            RecentCameraTile(it, Modifier.fillMaxWidth().aspectRatio(1f), selected.indexOf(it.uri) + 1, ::toggleMedia)
                        }
                    }
                }
            }
            }
        }
    }
}

@Composable
private fun CameraAction(label: String, icon: Int, enabled: Boolean = true, action: () -> Unit) {
    IconButton(action, Modifier.size(46.dp).background(Color.Black.copy(alpha = .55f), CircleShape), enabled = enabled) {
        Icon(painterResource(icon), label, tint = Color.White.copy(alpha = if (enabled) 1f else .45f))
    }
}

@Composable
private fun RecentCameraTile(item: CameraRecent, modifier: Modifier, position: Int, choose: (Uri) -> Unit) {
    val context = LocalContext.current
    val bitmap by produceState<android.graphics.Bitmap?>(null, item.uri) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.loadThumbnail(item.uri, android.util.Size(200, 200), null) }.getOrNull()
        }
    }
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(Color.DarkGray).clickable { choose(item.uri) },
        contentAlignment = Alignment.Center) {
        AsyncImage(bitmap, if (item.video) "Recent video" else "Recent photo", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        if (item.video) Icon(painterResource(R.drawable.ic_media_play), null, Modifier.size(17.dp), Color.White)
        if (position > 0) {
            val colors = MaterialTheme.colorScheme
            Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = .27f))
                .border(2.dp, colors.primary, RoundedCornerShape(8.dp)))
            Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp).background(colors.primary, CircleShape),
                contentAlignment = Alignment.Center) {
                Text(position.toString(), color = colors.onPrimary, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private fun cameraRecents(context: Context): List<CameraRecent> {
    val collection = MediaStore.Files.getContentUri("external")
    val type = MediaStore.Files.FileColumns.MEDIA_TYPE
    val image = MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE
    val video = MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
    return runCatching {
        context.contentResolver.query(collection, arrayOf(MediaStore.MediaColumns._ID, type), "$type IN (?,?)",
            arrayOf(image.toString(), video.toString()), "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { cursor ->
            buildList {
                while (size < 96 && cursor.moveToNext()) {
                    val isVideo = cursor.getInt(1) == video
                    val source = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    add(CameraRecent(ContentUris.withAppendedId(source, cursor.getLong(0)), isVideo))
                }
            }
        }.orEmpty()
    }.getOrDefault(emptyList())
}
