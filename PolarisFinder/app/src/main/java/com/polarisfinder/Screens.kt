package com.polarisfinder

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat

private const val DONE_DEG = 3.0

@Composable
fun MainScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current

    var hasCamera by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasCamera = it
    }
    if (!hasCamera) {
        CenteredColumn {
            Text("Polaris Finder needs the camera to photograph the stars.", textAlign = TextAlign.Center)
            Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) { Text("Grant camera access") }
        }
        return
    }

    val controller = remember { LifecycleCameraController(context) }
    DisposableEffect(owner) {
        controller.bindToLifecycle(owner)
        vm.controller = controller
        onDispose {
            controller.unbind()
            vm.controller = null
        }
    }

    val state by vm.state.collectAsState()
    val guidance by vm.guidance.collectAsState()

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PreviewView(ctx).apply {
                    this.controller = controller
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
            },
        )
        when (val s = state) {
            UiState.Capture -> CaptureOverlay(onShutter = vm::onShutter, onSettings = vm::openSettings)
            is UiState.Busy -> BusyOverlay(s.text)
            is UiState.Guiding -> GuideOverlay(guidance, s.attempt, onCancel = vm::reset)
            is UiState.Result -> ResultScreen(s, onDone = vm::reset)
            is UiState.Error -> CenteredColumn(scrim = true) {
                Text(s.message, color = Color.White, textAlign = TextAlign.Center)
                Button(onClick = vm::reset) { Text("OK") }
            }
            UiState.Settings -> SettingsScreen(vm.keyStore.apiKey, onSave = vm::saveApiKey, onBack = vm::reset)
        }
    }
}

@Composable
private fun CenteredColumn(scrim: Boolean = false, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(if (scrim) Color(0xCC000000) else Color.Transparent).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

@Composable
private fun CaptureOverlay(onShutter: () -> Unit, onSettings: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp)) {
        TextButton(onClick = onSettings, modifier = Modifier.align(Alignment.TopEnd)) { Text("Settings") }
        Text(
            "Point the phone at the northern sky and hold it as steady as you can.\nA dark, clear sky works best.",
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 48.dp),
        )
        Button(onClick = onShutter, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp)) {
            Text("Take photo")
        }
    }
}

@Composable
private fun BusyOverlay(text: String) = CenteredColumn(scrim = true) {
    CircularProgressIndicator()
    Text(text, color = Color.White, textAlign = TextAlign.Center)
}

@Composable
private fun GuideOverlay(g: GuidanceEngine.Guidance?, attempt: Int, onCancel: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0x66000000)).padding(24.dp)) {
        val remaining = g?.remainingDeg
        val color = if (remaining != null && remaining < DONE_DEG) Color(0xFF4CAF50) else Color.White

        Column(
            Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Canvas(Modifier.size(220.dp)) {
                rotate(degrees = (g?.arrowDeg ?: 0.0).toFloat()) {
                    val w = size.width
                    val h = size.height
                    val arrow = Path().apply {
                        moveTo(w / 2, h * 0.05f)
                        lineTo(w * 0.85f, h * 0.45f)
                        lineTo(w * 0.62f, h * 0.45f)
                        lineTo(w * 0.62f, h * 0.95f)
                        lineTo(w * 0.38f, h * 0.95f)
                        lineTo(w * 0.38f, h * 0.45f)
                        lineTo(w * 0.15f, h * 0.45f)
                        close()
                    }
                    drawPath(arrow, color)
                }
            }
            Text(
                if (remaining == null) "Waiting for sensors..." else "%.1f° remaining".format(remaining),
                color = color,
                fontSize = 32.sp,
            )
            Text(
                when {
                    remaining == null -> ""
                    remaining < DONE_DEG -> "Almost there - hold still, verifying..."
                    remaining > 90 -> "Polaris is behind you - turn around"
                    else -> "Rotate the phone (don't move it) in the arrow's direction"
                },
                color = Color.White,
                textAlign = TextAlign.Center,
            )
            if (attempt > 0) Text("Verification attempt $attempt", color = Color.LightGray)
        }
        TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.BottomCenter)) { Text("Cancel") }
    }
}

@Composable
private fun ResultScreen(s: UiState.Result, onDone: () -> Unit) {
    val bitmap = remember(s.photo) { BitmapFactory.decodeFile(s.photo.path) }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (bitmap != null) {
            val image = remember(bitmap) { bitmap.asImageBitmap() }
            Canvas(Modifier.fillMaxSize()) {
                val o = s.offset
                val scale = minOf(size.width / bitmap.width, size.height / bitmap.height)
                val dw = bitmap.width * scale
                val dh = bitmap.height * scale
                val ox = (size.width - dw) / 2
                val oy = (size.height - dh) / 2
                drawImage(image, dstOffset = IntOffset(ox.toInt(), oy.toInt()), dstSize = IntSize(dw.toInt(), dh.toInt()))
                // FITS pixels: x right, y up, 0-based centres. Image rows run top-down.
                val cx = ox + ((o.pixelX + 0.5) / o.width * dw).toFloat()
                val cy = oy + ((o.height - 1 - o.pixelY + 0.5) / o.height * dh).toFloat()
                drawCircle(
                    Color.Cyan,
                    radius = (o.circleRadiusPx / o.width * dw).toFloat(),
                    center = Offset(cx, cy),
                    style = Stroke(width = 3.dp.toPx()),
                )
            }
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xAA000000)).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Polaris found (circled)", color = Color.Cyan, fontSize = 20.sp)
            Button(onClick = onDone) { Text("Start over") }
        }
    }
}

@Composable
private fun SettingsScreen(current: String, onSave: (String) -> Unit, onBack: () -> Unit) {
    var key by remember { mutableStateOf(current) }
    CenteredColumn(scrim = true) {
        Text("astrometry.net API key", color = Color.White)
        OutlinedTextField(value = key, onValueChange = { key = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("Get a free key at nova.astrometry.net (log in -> API).", color = Color.LightGray, textAlign = TextAlign.Center)
        Button(onClick = { onSave(key) }) { Text("Save") }
        TextButton(onClick = onBack) { Text("Back") }
    }
}
