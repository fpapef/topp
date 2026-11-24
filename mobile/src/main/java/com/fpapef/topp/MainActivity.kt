package com.fpapef.topp

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private val app get() = application as ToppApp
    private var previewView: PreviewView? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var analysis: ImageAnalysis? = null
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val vision by lazy { Vision(applicationContext, app.follow, app::log) }
    private var orientationListener: OrientationEventListener? = null
    private var recording: Recording? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var lastRecToggle = 0L

    private val permissions = arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
    )

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        onPermissions()
    }

    private val locationLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) app.follow.onLocationPermission()
        else app.log("Position refusée : le GPS du suivi ne marchera pas")
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        setContent {
            ToppScreen(
                app = app,
                onPreview = { pv -> previewView = pv; if (granted(Manifest.permission.CAMERA)) bindCamera() },
                onRecord = ::toggleRecording,
                onFlipCamera = ::switchCamera,
            )
        }
        lifecycleScope.launch {
            app.uiCommands.collect {
                when (it) {
                    "rec" -> toggleRecording()
                    "flip" -> switchCamera()
                }
            }
        }
        lifecycleScope.launch {
            app.follow.needLocationPermission.collect {
                if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
                    locationLauncher.launch(
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                    )
                }
            }
        }
        // Orientation réelle du téléphone, même si la rotation automatique de l'écran est coupée
        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(o: Int) {
                if (o == ORIENTATION_UNKNOWN) return
                val r = when (o) {
                    in 45 until 135 -> 270
                    in 135 until 225 -> 180
                    in 225 until 315 -> 90
                    else -> 0
                }
                app.follow.physicalRotation = r
            }
        }
        updateDisplayRotation()
        val missing = permissions.filterNot(::granted)
        if (missing.isEmpty()) onPermissions() else permLauncher.launch(missing.toTypedArray())
    }

    private fun onPermissions() {
        if (granted(Manifest.permission.BLUETOOTH_SCAN) && granted(Manifest.permission.BLUETOOTH_CONNECT)) {
            app.ble.connect()
        } else {
            app.log("Autorisation « Appareils à proximité » refusée : impossible de joindre la nacelle")
        }
        if (granted(Manifest.permission.CAMERA)) bindCamera()
    }

    private fun bindCamera() {
        val pv = previewView ?: return
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            runCatching {
                val provider = future.get()
                val wide = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                    .build()
                val preview = Preview.Builder().setResolutionSelector(wide).build()
                    .also { it.setSurfaceProvider(pv.surfaceProvider) }
                val an = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                            .setResolutionStrategy(
                                ResolutionStrategy(Size(960, 540), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                            )
                            .build()
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                    .also { it.setAnalyzer(analysisExecutor, vision) }
                val recorder = Recorder.Builder()
                    .setQualitySelector(
                        QualitySelector.from(Quality.FHD, FallbackStrategy.lowerQualityOrHigherThan(Quality.HD))
                    )
                    .build()
                val vc = VideoCapture.withOutput(recorder)
                display?.let { vc.targetRotation = it.rotation; an.targetRotation = it.rotation }
                provider.unbindAll()
                val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
                runCatching { provider.bindToLifecycle(this, selector, preview, vc, an) }
                    .onSuccess { analysis = an }
                    .onFailure {
                        // Combinaison refusée par la caméra : on garde l'aperçu et la vidéo, sans suivi caméra
                        app.log("Caméra : suivi par l'image indisponible (${it.message})")
                        provider.unbindAll()
                        provider.bindToLifecycle(this, selector, preview, vc)
                        analysis = null
                    }
                vision.front = lensFacing == CameraSelector.LENS_FACING_FRONT
                app.follow.setLens(vision.front)
                videoCapture = vc
            }.onFailure { app.log("Caméra : ${it.message}") }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun switchCamera() {
        if (recording != null) { toast("Arrête l'enregistrement d'abord"); return }
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT
        else CameraSelector.LENS_FACING_BACK
        bindCamera()
    }

    @SuppressLint("MissingPermission")
    private fun toggleRecording() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastRecToggle < 800) return
        lastRecToggle = now
        val vc = videoCapture ?: run { toast("Caméra pas prête"); return }
        recording?.let { it.stop(); recording = null; return }

        val name = "Topp_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.FRANCE).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Topp")
        }
        val options = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(values)
            .build()
        var pending = vc.output.prepareRecording(this, options)
        if (granted(Manifest.permission.RECORD_AUDIO)) pending = pending.withAudioEnabled()
        recording = pending.start(ContextCompat.getMainExecutor(this)) { ev ->
            when (ev) {
                is VideoRecordEvent.Start -> {
                    app.recStartedAt.value = SystemClock.elapsedRealtime()
                    app.recording.value = true
                    app.log("Enregistrement en cours")
                }
                is VideoRecordEvent.Finalize -> {
                    app.recording.value = false
                    recording = null
                    if (ev.hasError() && ev.error != VideoRecordEvent.Finalize.ERROR_NONE) {
                        app.log("Vidéo : erreur ${ev.error}")
                    } else {
                        app.log("Vidéo enregistrée (Films/Topp)")
                        toast("Vidéo enregistrée dans Films/Topp")
                    }
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        display?.let { d ->
            videoCapture?.targetRotation = d.rotation
            analysis?.targetRotation = d.rotation
        }
        updateDisplayRotation()
    }

    private fun updateDisplayRotation() {
        app.follow.displayRotation = when (display?.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }

    override fun onResume() {
        super.onResume()
        orientationListener?.takeIf { it.canDetectOrientation() }?.enable()
    }

    override fun onPause() {
        super.onPause()
        orientationListener?.disable()
    }

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.execute { vision.close() }
        analysisExecutor.shutdown()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}

private enum class Sheet { NONE, SETTINGS, JOURNAL, PICKER }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToppScreen(
    app: ToppApp,
    onPreview: (PreviewView) -> Unit,
    onRecord: () -> Unit,
    onFlipCamera: () -> Unit,
) {
    val s by app.ble.state.collectAsStateWithLifecycle()
    val rec by app.recording.collectAsStateWithLifecycle()
    val recStart by app.recStartedAt.collectAsStateWithLifecycle()
    val logs by app.logs.collectAsStateWithLifecycle()
    val devices by app.ble.seen.collectAsStateWithLifecycle()
    val watchAt by app.wear.lastWatchMessage.collectAsStateWithLifecycle()
    val gestures by app.gesturesActive.collectAsStateWithLifecycle()
    val follow by app.follow.ui.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var sheet by remember { mutableStateOf(Sheet.NONE) }
    LaunchedEffect(Unit) {
        while (true) { delay(500); now = SystemClock.elapsedRealtime() }
    }
    val c = app.controller
    val att = s.attitude
    val ui = UiState(
        gimbal = s.status,
        modeLabel = Hohem.modeLabel(s.mode),
        watchConnected = watchAt != 0L && now - watchAt < 6_000,
        recording = rec,
        recSeconds = if (rec && recStart > 0) (now - recStart) / 1000 else 0,
        relPan = att?.let { Hohem.wrap180(it.encPan - c.homePan) },
        relTilt = att?.let { it.encTilt - c.homeTilt },
        recentering = c.recentering,
        gesturesActive = gestures,
        follow = follow,
    )
    val actions = remember {
        object : UiActions {
            override fun record() = onRecord()
            override fun mode() = app.cycleMode()
            override fun recenter() = c.recenter()
            override fun flipCamera() = onFlipCamera()
            override fun settings() { sheet = Sheet.SETTINGS }
            override fun chooseGimbal() { app.ble.connect(); sheet = Sheet.PICKER }
            override fun joystick(x: Float, y: Float) = c.joystick(x, y)
            override fun follow() { app.follow.toggle() }
            override fun pickPerson(x: Float, y: Float) = app.follow.pick(x, y)
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = Tk.Amber, surface = Tk.Panel, background = Color.Black)) {
        ToppHud(ui, actions) {
            AndroidView(factory = { ctx -> PreviewView(ctx).also(onPreview) }, modifier = Modifier.fillMaxSize())
        }

        if (sheet != Sheet.NONE) {
            ModalBottomSheet(
                onDismissRequest = { sheet = Sheet.NONE },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = Tk.Panel,
                scrimColor = Color(0x80000000),
            ) {
                when (sheet) {
                    Sheet.SETTINGS -> {
                        var speed by remember { mutableFloatStateOf(c.speed) }
                        var invX by remember { mutableStateOf(c.invertX) }
                        var invY by remember { mutableStateOf(c.invertY) }
                        var gHold by remember { mutableStateOf(c.gestureHold) }
                        var gSens by remember { mutableFloatStateOf(c.gestureSens) }
                        var gInvX by remember { mutableStateOf(c.gestureInvX) }
                        var gInvY by remember { mutableStateOf(c.gestureInvY) }
                        var fMode by remember { mutableStateOf(app.follow.mode) }
                        var fGain by remember { mutableFloatStateOf(app.follow.gain) }
                        SettingsContent(
                            SettingsValues(
                                speed, invX, invY, s.status != GimbalBle.Status.OFF,
                                gestureHold = gHold, gestureSens = gSens, gestureInvX = gInvX, gestureInvY = gInvY,
                                followMode = fMode, followGain = fGain,
                            ),
                            object : SettingsActions {
                                override fun speed(v: Float) { speed = v; c.speed = v }
                                override fun invertX(v: Boolean) { invX = v; c.invertX = v }
                                override fun invertY(v: Boolean) { invY = v; c.invertY = v }
                                override fun gestureHold(v: Boolean) { gHold = v; c.gestureHold = v }
                                override fun gestureSens(v: Float) { gSens = v; c.gestureSens = v }
                                override fun gestureInvX(v: Boolean) { gInvX = v; c.gestureInvX = v }
                                override fun gestureInvY(v: Boolean) { gInvY = v; c.gestureInvY = v }
                                override fun followMode(m: FollowMode) { fMode = m; app.follow.mode = m }
                                override fun followGain(v: Float) { fGain = v; app.follow.gain = v }
                                override fun followRelearn() = app.follow.relearn()
                                override fun followRecalibrate() = app.follow.recalibrate()
                                override fun setHome() { c.setHomeHere() }
                                override fun relearn() = c.resetLearning()
                                override fun toggleConnection() {
                                    if (s.status != GimbalBle.Status.OFF) app.ble.disconnect() else app.ble.connect()
                                }
                                override fun choose() { app.ble.connect(); sheet = Sheet.PICKER }
                                override fun journal() { sheet = Sheet.JOURNAL }
                            },
                        )
                    }
                    Sheet.JOURNAL -> JournalContent(logs)
                    Sheet.PICKER -> PickerContent(devices) { addr -> app.ble.connectAddress(addr); sheet = Sheet.NONE }
                    Sheet.NONE -> Unit
                }
            }
        }
    }
}
