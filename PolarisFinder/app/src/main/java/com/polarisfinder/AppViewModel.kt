package com.polarisfinder

import android.app.Application
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.view.LifecycleCameraController
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed interface UiState {
    data object Capture : UiState
    data class Busy(val text: String) : UiState
    data class Guiding(val attempt: Int) : UiState
    data class Result(val photo: File, val offset: PolarisOffset) : UiState
    data class Error(val message: String) : UiState
    data object Settings : UiState
}

class AppViewModel(private val app: Application) : AndroidViewModel(app) {

    val tracker = OrientationTracker(app)
    val keyStore = ApiKeyStore(app)
    private val solver by lazy { PolarisSolver(app) }

    /** Set by the UI while the camera is bound. */
    var controller: LifecycleCameraController? = null

    private val _state = MutableStateFlow<UiState>(UiState.Capture)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _guidance = MutableStateFlow<GuidanceEngine.Guidance?>(null)
    val guidance: StateFlow<GuidanceEngine.Guidance?> = _guidance.asStateFlow()

    private var work: Job? = null
    private var shotCounter = 0

    init {
        tracker.start()
    }

    override fun onCleared() {
        tracker.stop()
    }

    fun onShutter() = runPipeline(attempt = 0)

    fun reset() {
        work?.cancel()
        _guidance.value = null
        _state.value = UiState.Capture
    }

    fun openSettings() { _state.value = UiState.Settings }

    fun saveApiKey(key: String) {
        keyStore.apiKey = key
        reset()
    }

    /** Take photo -> make upright -> astrometry.net -> Polaris offset -> result or guidance. */
    private fun runPipeline(attempt: Int) {
        val ctrl = controller ?: return
        if (!tracker.available) return fail("This phone has no gyroscope or rotation sensor.")
        val key = keyStore.apiKey
        if (key.isBlank()) return fail("Enter your astrometry.net API key in Settings first.")
        // Orientation at the moment of the shutter; the guidance is relative to this.
        val r0 = tracker.latest() ?: return fail("No orientation data yet - move the phone a little and retry.")

        work?.cancel()
        work = viewModelScope.launch {
            try {
                val n = ++shotCounter
                val dir = app.cacheDir
                val raw = File(dir, "raw_$n.jpg")
                val photo = File(dir, "photo_$n.jpg")
                val fits = File(dir, "solved_$n.fits")

                _state.value = UiState.Busy(
                    if (attempt == 0) "Hold still - taking photo..." else "Verifying - hold still...",
                )
                takePicture(ctrl, raw)

                _state.value = UiState.Busy("Preparing image...")
                val offset = withContext(Dispatchers.IO) {
                    ImageUtils.makeUpright(raw, photo)
                    raw.delete()
                    solver.solve(photo.path, key, fits.path) { _state.value = UiState.Busy(it) }
                    solver.offset(fits.path).also { fits.delete() }
                }

                when {
                    offset.inside -> {
                        _guidance.value = null
                        _state.value = UiState.Result(photo, offset)
                    }
                    attempt >= MAX_ATTEMPTS ->
                        fail("Could not get Polaris into the frame after $MAX_ATTEMPTS tries. Start over.")
                    else -> startGuiding(offset, r0, attempt)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e.message ?: e.toString())
            }
        }
    }

    private fun startGuiding(offset: PolarisOffset, r0: FloatArray, attempt: Int) {
        val engine = GuidanceEngine(r0, offset.sepDeg, offset.thetaDeg)
        _state.value = UiState.Guiding(attempt)
        work = viewModelScope.launch {
            var steadySince = 0L
            tracker.samples.filterNotNull().collect { s ->
                val g = engine.update(s.rotation)
                _guidance.value = g
                if (g.remainingDeg < VERIFY_WITHIN_DEG && s.rateDegPerSec < STEADY_DEG_PER_SEC) {
                    if (steadySince == 0L) {
                        steadySince = s.tNanos
                    } else if (s.tNanos - steadySince > STEADY_NANOS) {
                        runPipeline(attempt + 1) // cancels this collector and starts the verify shot
                    }
                } else {
                    steadySince = 0L
                }
            }
        }
    }

    private fun fail(message: String) {
        _state.value = UiState.Error(message)
    }

    private suspend fun takePicture(ctrl: LifecycleCameraController, file: File) =
        suspendCancellableCoroutine { cont ->
            ctrl.takePicture(
                ImageCapture.OutputFileOptions.Builder(file).build(),
                ContextCompat.getMainExecutor(app),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) = cont.resume(Unit)
                    override fun onError(exception: ImageCaptureException) = cont.resumeWithException(exception)
                },
            )
        }

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val VERIFY_WITHIN_DEG = 3.0 // start the verification shot inside this angle...
        const val STEADY_DEG_PER_SEC = 5.0 // ...while rotating slower than this...
        const val STEADY_NANOS = 1_000_000_000L // ...for this long.
    }
}
