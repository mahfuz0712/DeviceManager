package com.device.mux.manager

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.KeyEvent
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

object Tests {
    /** "display" or "touch" while a full screen test is running (drawn by the App on top of everything). */
    var full by mutableStateOf<String?>(null)
}

object KeyTest {
    var active by mutableStateOf(false)
    var last by mutableStateOf("")
    val down = mutableStateMapOf<Int, Boolean>()
    val count = mutableStateMapOf<Int, Int>()

    fun onKey(e: KeyEvent) {
        last = KeyEvent.keyCodeToString(e.keyCode)
        if (e.action == KeyEvent.ACTION_DOWN) {
            if (e.repeatCount == 0) count[e.keyCode] = (count[e.keyCode] ?: 0) + 1
            down[e.keyCode] = true
        } else if (e.action == KeyEvent.ACTION_UP) {
            down[e.keyCode] = false
        }
    }
}

@Composable
fun TestsScreen(back: () -> Unit) {
    var sel by remember { mutableStateOf<String?>(null) }
    BackHandler(enabled = sel != null) { sel = null }
    val up = { sel = null }
    when (sel) {
        null -> Page {
            BackRow("HARDWARE TESTS", back)
            TestItem("DISPLAY TEST", "Full screen colors to find dead pixels and burn-in") { Tests.full = "display" }
            TestItem("TOUCH TEST", "Draw on screen, shows multi-touch count") { Tests.full = "touch" }
            TestItem("SPEAKER TEST", "Left, right and both channels with selectable tone") { sel = "speaker" }
            TestItem("MICROPHONE TEST", "Live input level meter") { sel = "mic" }
            TestItem("SENSORS TEST", "Every sensor with live values") { sel = "sensors" }
            TestItem("BUTTONS TEST", "Volume and other hardware keys") { sel = "buttons" }
            TestItem("CAMERA TEST", "All cameras: info, live preview, torch") { sel = "camera" }
            TestItem("VIBRATION TEST", "Short, long and pattern vibration") { sel = "vibe" }
            TestItem("INTERNET TEST", "DNS, latency, download and upload speed") { sel = "net" }
            TestItem("HARDWARE FEATURES", "What this phone supports: NFC, fingerprint, sensors and more") { sel = "features" }
        }
        "speaker" -> SpeakerTest(up)
        "mic" -> MicTest(up)
        "sensors" -> SensorsTest(up)
        "buttons" -> ButtonsTest(up)
        "camera" -> CameraTest(up)
        "vibe" -> VibrationTest(up)
        "net" -> InternetTest(up)
        "features" -> FeaturesTest(up)
    }
}

@Composable
private fun TestItem(title: String, desc: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().border(1.dp, cDim, HudShape(8.dp)).clickable(onClick = onClick).padding(14.dp)) {
        T(title, 14f, cMain, true)
        T(desc, 10f, cSub, lines = 2, mod = Modifier.padding(top = 2.dp))
    }
}

// ---------------------------------------------------------------------- full screen tests

@Composable
fun FullScreenTest(kind: String, exit: () -> Unit) {
    BackHandler { exit() }
    if (kind == "display") DisplayFull(exit) else TouchFull(exit)
}

@Composable
private fun DisplayFull(exit: () -> Unit) {
    var step by remember { mutableIntStateOf(0) }
    val names = listOf("RED", "GREEN", "BLUE", "WHITE", "BLACK", "GRAY GRADIENT", "COLOR BARS")
    val bg: Modifier = when (step) {
        0 -> Modifier.background(Color.Red)
        1 -> Modifier.background(Color.Green)
        2 -> Modifier.background(Color.Blue)
        3 -> Modifier.background(Color.White)
        4 -> Modifier.background(Color.Black)
        5 -> Modifier.background(Brush.horizontalGradient(listOf(Color.Black, Color.White)))
        else -> Modifier.background(
            Brush.horizontalGradient(listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta))
        )
    }
    val textColor = if (step == 3 || step == 1) Color.Black else Color.White
    Box(Modifier.fillMaxSize().then(bg).clickable { if (step >= names.lastIndex) exit() else step++ }, Alignment.BottomCenter) {
        T("${names[step]}  (${step + 1}/${names.size})   TAP = NEXT, BACK = EXIT", 11f, textColor, mod = Modifier.padding(bottom = 40.dp))
    }
}

@Composable
private fun TouchFull(exit: () -> Unit) {
    val pts = remember { mutableStateListOf<Offset>() }
    var maxTouch by remember { mutableIntStateOf(0) }
    var now by remember { mutableIntStateOf(0) }
    val main = cMain
    Box(
        Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val ev = awaitPointerEvent()
                    val d = ev.changes.count { it.pressed }
                    now = d
                    if (d > maxTouch) maxTouch = d
                    ev.changes.forEach {
                        if (it.pressed) {
                            pts.add(it.position)
                            if (pts.size > 5000) pts.removeAt(0)
                        }
                    }
                }
            }
        }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val step = 60.dp.toPx()
            var x = 0f
            while (x < size.width) { drawLine(Color(0x33FFFFFF), Offset(x, 0f), Offset(x, size.height)); x += step }
            var y = 0f
            while (y < size.height) { drawLine(Color(0x33FFFFFF), Offset(0f, y), Offset(size.width, y)); y += step }
            for (p in pts) drawCircle(main, 14f, p)
        }
        Column(Modifier.align(Alignment.TopCenter).padding(top = 60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            T("TOUCH TEST: draw everywhere, corners and edges too", 11f, Color.White)
            T("TOUCHING NOW: $now   MAX AT ONCE: $maxTouch", 12f, cWarn, true)
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Chip("CLEAR", false, Modifier.padding(0.dp)) { pts.clear(); maxTouch = 0 }
                Chip("EXIT", true, Modifier.padding(0.dp)) { exit() }
            }
        }
    }
}

// ---------------------------------------------------------------------- speaker

private suspend fun playTone(freq: Int, left: Boolean, right: Boolean, ms: Int = 1200) = withContext(Dispatchers.IO) {
    val sr = 44100
    val n = sr * ms / 1000
    val buf = ShortArray(n * 2)
    val fade = sr / 100
    for (i in 0 until n) {
        var a = 0.6
        if (i < fade) a *= i / fade.toDouble()
        if (i > n - fade) a *= (n - i) / fade.toDouble()
        val v = (sin(2.0 * PI * freq * i / sr) * a * Short.MAX_VALUE).toInt().toShort()
        buf[2 * i] = if (left) v else 0
        buf[2 * i + 1] = if (right) v else 0
    }
    val track = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        )
        .setAudioFormat(
            AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sr)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()
        )
        .setBufferSizeInBytes(buf.size * 2)
        .setTransferMode(AudioTrack.MODE_STATIC)
        .build()
    try {
        track.write(buf, 0, buf.size)
        track.play()
        delay(ms + 150L)
    } finally {
        track.release()
    }
}

@Composable
private fun SpeakerTest(back: () -> Unit) {
    val scope = rememberCoroutineScope()
    var freq by remember { mutableIntStateOf(1000) }
    var playing by remember { mutableStateOf("") }
    fun play(l: Boolean, r: Boolean, label: String) {
        if (playing.isNotEmpty()) return
        scope.launch {
            playing = label
            try { playTone(freq, l, r) } catch (e: Exception) { }
            playing = ""
        }
    }
    Page {
        BackRow("SPEAKER TEST", back)
        Panel("TONE") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (f in listOf(250, 1000, 4000, 8000)) Chip("$f Hz", freq == f, Modifier.weight(1f)) { freq = f }
            }
            T("Turn the media volume up. You should hear the tone only from the chosen side.", 10f, cSub, lines = 3, mod = Modifier.padding(vertical = 10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip(if (playing == "L") "PLAYING..." else "LEFT", playing == "L", Modifier.weight(1f)) { play(true, false, "L") }
                Chip(if (playing == "B") "PLAYING..." else "BOTH", playing == "B", Modifier.weight(1f)) { play(true, true, "B") }
                Chip(if (playing == "R") "PLAYING..." else "RIGHT", playing == "R", Modifier.weight(1f)) { play(false, true, "R") }
            }
        }
    }
}

// ---------------------------------------------------------------------- microphone

@Composable
private fun MicTest(back: () -> Unit) {
    val ctx = LocalContext.current
    val ptick = Perms.tick
    val has = remember(ptick) { Perms.has(ctx, Manifest.permission.RECORD_AUDIO) }
    var level by remember { mutableFloatStateOf(0f) }
    var db by remember { mutableFloatStateOf(-90f) }
    var peak by remember { mutableFloatStateOf(-90f) }
    val hist = remember { mutableStateListOf<Float>() }

    LaunchedEffect(has) {
        if (!has) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val sr = 44100
            val min = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            @SuppressLint("MissingPermission")
            val ar = AudioRecord(MediaRecorder.AudioSource.MIC, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 4096))
            try {
                ar.startRecording()
                val buf = ShortArray(2048)
                while (isActive) {
                    val n = ar.read(buf, 0, buf.size)
                    if (n > 0) {
                        var sum = 0.0
                        for (i in 0 until n) sum += buf[i].toDouble() * buf[i].toDouble()
                        val rms = sqrt(sum / n)
                        val d = (20.0 * log10(rms / 32768.0 + 1e-9)).toFloat()
                        db = d
                        if (d > peak) peak = d
                        level = ((d + 70f) / 70f).coerceIn(0f, 1f)
                        hist.add(d.coerceAtLeast(-90f))
                        if (hist.size > 150) hist.removeAt(0)
                    }
                }
            } finally {
                try { ar.stop() } catch (e: Exception) { }
                ar.release()
            }
        }
    }
    Page {
        BackRow("MICROPHONE TEST", back)
        if (!has) {
            T("Microphone permission is needed.", 12f, cWarn)
            Chip("GRANT MICROPHONE", false, Modifier.fillMaxWidth()) { Perms.request(Manifest.permission.RECORD_AUDIO) }
            return@Page
        }
        Panel("INPUT LEVEL") {
            T("${f1(db)} dBFS", 28f, loadColor(level), true, Modifier.fillMaxWidth(), align = TextAlign.Center)
            Bar(level, loadColor(level), Modifier.padding(vertical = 8.dp))
            KV("PEAK", "${f1(peak)} dBFS")
            T("Speak or tap near the microphone. The bar must move.", 10f, cSub, lines = 2, mod = Modifier.padding(vertical = 6.dp))
            LineGraph(listOf(Series(hist, cMain)), -90f, 0f, " dB")
            Chip("RESET PEAK", false, Modifier.fillMaxWidth().padding(top = 8.dp)) { peak = -90f }
        }
    }
}

// ---------------------------------------------------------------------- sensors

@Composable
private fun SensorsTest(back: () -> Unit) {
    val ctx = LocalContext.current
    val sm = remember { ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    val list = remember { sm.getSensorList(Sensor.TYPE_ALL) }
    val vals = remember { mutableStateMapOf<Int, String>() }
    DisposableEffect(Unit) {
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val i = list.indexOf(e.sensor)
                if (i >= 0) vals[i] = e.values.take(3).joinToString("  ") { f2(it) }
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        for (s in list) {
            try {
                if (s.reportingMode != Sensor.REPORTING_MODE_ONE_SHOT) sm.registerListener(l, s, SensorManager.SENSOR_DELAY_UI)
            } catch (e: Exception) { }
        }
        onDispose { sm.unregisterListener(l) }
    }
    Page {
        BackRow("SENSORS TEST (${list.size})", back)
        T("Move, rotate, cover the top of the phone, shine a light. Values must change.", 10f, cSub, lines = 2)
        Panel("ALL SENSORS") {
            list.forEachIndexed { i, s ->
                val v = vals[i]
                val type = (s.stringType ?: "").removePrefix("android.sensor.")
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    T(s.name, 11f, cText, true, lines = 2)
                    T("$type  ${s.vendor}  range ${f1(s.maximumRange)}", 9f, cSub, lines = 2)
                    T(
                        if (s.reportingMode == Sensor.REPORTING_MODE_ONE_SHOT) "TRIGGER SENSOR (event based)" else v ?: "NO DATA YET",
                        10f, if (v != null) cGood else cSub
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------- buttons

@Composable
private fun ButtonsTest(back: () -> Unit) {
    DisposableEffect(Unit) {
        KeyTest.active = true
        onDispose { KeyTest.active = false }
    }
    val keys = listOf(
        KeyEvent.KEYCODE_VOLUME_UP to "VOLUME UP",
        KeyEvent.KEYCODE_VOLUME_DOWN to "VOLUME DOWN",
        KeyEvent.KEYCODE_MENU to "MENU",
        KeyEvent.KEYCODE_CAMERA to "CAMERA",
        KeyEvent.KEYCODE_HEADSETHOOK to "HEADSET HOOK",
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE to "MEDIA PLAY/PAUSE",
        KeyEvent.KEYCODE_MEDIA_NEXT to "MEDIA NEXT",
        KeyEvent.KEYCODE_MEDIA_PREVIOUS to "MEDIA PREVIOUS"
    )
    Page {
        BackRow("BUTTONS TEST", back)
        T("Press the hardware buttons. Each one lights up. The back button exits this test.", 10f, cSub, lines = 2)
        Panel("KEYS") {
            for ((code, name) in keys) {
                val d = KeyTest.down[code] == true
                val c = KeyTest.count[code] ?: 0
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp).border(1.dp, if (d) cGood else cDim)
                        .background(if (d) cGood.copy(alpha = 0.25f) else Color.Transparent).padding(10.dp)
                ) {
                    T(name, 12f, if (c > 0) cGood else cText, true, Modifier.weight(1f))
                    T(if (d) "PRESSED" else if (c > 0) "OK x$c" else "NOT PRESSED", 11f, if (c > 0) cGood else cSub)
                }
            }
            KV("LAST KEY EVENT", KeyTest.last.ifEmpty { "-" })
        }
        T("The power button cannot be detected by apps. Test it by turning the screen off and on.", 9f, cSub, lines = 2)
    }
}

// ---------------------------------------------------------------------- camera

private class CamInfo(
    val id: String, val facing: String, val mp: Float, val focal: String, val aperture: String,
    val flash: Boolean, val ois: Boolean, val level: String, val physical: String
)

private fun camInfos(ctx: Context): List<CamInfo> {
    val mgr = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    return try {
        mgr.cameraIdList.map { id ->
            val c = mgr.getCameraCharacteristics(id)
            val px = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
            CamInfo(
                id = id,
                facing = when (c.get(CameraCharacteristics.LENS_FACING)) {
                    CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
                    CameraCharacteristics.LENS_FACING_BACK -> "BACK"
                    else -> "EXTERNAL"
                },
                mp = if (px != null) px.width.toFloat() * px.height / 1_000_000f else 0f,
                focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.joinToString(", ") { f1(it) + " mm" } ?: "N/A",
                aperture = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.joinToString(", ") { "f/" + f1(it) } ?: "N/A",
                flash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true,
                ois = (c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)?.any { it == 1 } ?: false),
                level = when (c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
                    0 -> "LIMITED"; 1 -> "FULL"; 2 -> "LEGACY"; 3 -> "LEVEL 3"; 4 -> "EXTERNAL"; else -> "?"
                },
                physical = if (Build.VERSION.SDK_INT >= 28) c.physicalCameraIds.joinToString(", ").ifEmpty { "-" } else "-"
            )
        }
    } catch (e: Exception) { emptyList() }
}

@Composable
private fun CameraTest(back: () -> Unit) {
    val ctx = LocalContext.current
    val ptick = Perms.tick
    val has = remember(ptick) { Perms.has(ctx, Manifest.permission.CAMERA) }
    val cams = remember { camInfos(ctx) }
    var active by remember { mutableStateOf<String?>(null) }
    val torch = remember { mutableStateMapOf<String, Boolean>() }
    val mgr = remember { ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager }
    DisposableEffect(Unit) {
        onDispose { for ((id, on) in torch) if (on) try { mgr.setTorchMode(id, false) } catch (e: Exception) { } }
    }
    Page {
        BackRow("CAMERA TEST (${cams.size})", back)
        if (!has) {
            T("Camera permission is needed for the live preview.", 12f, cWarn)
            Chip("GRANT CAMERA", false, Modifier.fillMaxWidth()) { Perms.request(Manifest.permission.CAMERA) }
        }
        if (cams.isEmpty()) T("NO CAMERA FOUND", 12f, cBad)
        for (c in cams) {
            Panel("CAMERA ${c.id}  ${c.facing}") {
                KV("RESOLUTION", "${f1(c.mp)} MP")
                KV("FOCAL LENGTH", c.focal)
                KV("APERTURE", c.aperture)
                KV("FLASH", if (c.flash) "YES" else "NO")
                KV("OPTICAL STABILIZATION", if (c.ois) "YES" else "NO")
                KV("HARDWARE LEVEL", c.level)
                KV("PHYSICAL LENSES", c.physical)
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip(if (active == c.id) "CLOSE PREVIEW" else "LIVE PREVIEW", active == c.id, Modifier.weight(1f)) {
                        if (has) {
                            active = if (active == c.id) null else c.id
                        } else {
                            Perms.request(Manifest.permission.CAMERA)
                        }
                    }
                    if (c.flash) {
                        Chip(if (torch[c.id] == true) "TORCH: ON" else "TORCH: OFF", torch[c.id] == true, Modifier.weight(1f)) {
                            val on = torch[c.id] != true
                            try { mgr.setTorchMode(c.id, on); torch[c.id] = on } catch (e: Exception) { }
                        }
                    }
                }
                if (active == c.id && has) {
                    CameraPreview(c.id, Modifier.fillMaxWidth().height(380.dp).padding(top = 8.dp))
                }
            }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun CameraPreview(id: String, modifier: Modifier) {
    val ctx = LocalContext.current
    val tv = remember(id) { TextureView(ctx) }
    DisposableEffect(id) {
        val mgr = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val thread = HandlerThread("cam-$id").also { it.start() }
        val handler = Handler(thread.looper)
        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        var closed = false
        var bufW = 1280
        var bufH = 720

        fun transform() {
            val vw = tv.width.toFloat()
            val vh = tv.height.toFloat()
            if (vw <= 0f || vh <= 0f) return
            try {
                val chars = mgr.getCameraCharacteristics(id)
                val sensor = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
                val front = chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
                @Suppress("DEPRECATION")
                val rotation = (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
                val disp = rotation * 90
                val rot = if (front) (sensor + disp) % 360 else (sensor - disp + 360) % 360
                val m = Matrix()
                val cx = vw / 2f
                val cy = vh / 2f
                m.postScale(bufW / vw, bufH / vh, cx, cy)
                m.postRotate(rot.toFloat(), cx, cy)
                val rw = if (rot == 90 || rot == 270) bufH.toFloat() else bufW.toFloat()
                val rh = if (rot == 90 || rot == 270) bufW.toFloat() else bufH.toFloat()
                val s = maxOf(vw / rw, vh / rh)
                m.postScale(s, s, cx, cy)
                if (front) m.postScale(-1f, 1f, cx, cy)
                tv.setTransform(m)
            } catch (e: Exception) { }
        }

        fun start(st: SurfaceTexture) {
            try {
                val chars = mgr.getCameraCharacteristics(id)
                val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val sizes = map?.getOutputSizes(SurfaceTexture::class.java)
                val sz = sizes?.filter { it.width <= 1920 && it.height <= 1080 }?.maxByOrNull { it.width.toLong() * it.height }
                if (sz != null) { bufW = sz.width; bufH = sz.height }
                st.setDefaultBufferSize(bufW, bufH)
                mgr.openCamera(id, object : CameraDevice.StateCallback() {
                    override fun onOpened(cam: CameraDevice) {
                        if (closed) { cam.close(); return }
                        device = cam
                        try {
                            val surface = Surface(st)
                            val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                            req.addTarget(surface)
                            cam.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(s: CameraCaptureSession) {
                                    if (closed) return
                                    session = s
                                    try { s.setRepeatingRequest(req.build(), null, handler) } catch (e: Exception) { }
                                }
                                override fun onConfigureFailed(s: CameraCaptureSession) {}
                            }, handler)
                        } catch (e: Exception) { }
                    }
                    override fun onDisconnected(cam: CameraDevice) { cam.close() }
                    override fun onError(cam: CameraDevice, error: Int) { cam.close() }
                }, handler)
            } catch (e: Exception) { }
        }

        tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { start(st); transform() }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) { transform() }
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        onDispose {
            closed = true
            try { session?.close() } catch (e: Exception) { }
            try { device?.close() } catch (e: Exception) { }
            tv.surfaceTextureListener = null
            thread.quitSafely()
        }
    }
    AndroidView(factory = { tv }, modifier = modifier)
}

// ---------------------------------------------------------------------- vibration

@Composable
private fun VibrationTest(back: () -> Unit) {
    val ctx = LocalContext.current
    val vib: Vibrator? = remember {
        if (Build.VERSION.SDK_INT >= 31) {
            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }
    Page {
        BackRow("VIBRATION TEST", back)
        Panel("MOTOR") {
            KV("VIBRATOR", if (vib?.hasVibrator() == true) "PRESENT" else "NOT FOUND", if (vib?.hasVibrator() == true) cGood else cBad)
            if (Build.VERSION.SDK_INT >= 26 && vib != null) KV("AMPLITUDE CONTROL", if (vib.hasAmplitudeControl()) "YES" else "NO")
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("SHORT", false, Modifier.weight(1f)) {
                    vib?.vibrate(VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE))
                }
                Chip("LONG", false, Modifier.weight(1f)) {
                    vib?.vibrate(VibrationEffect.createOneShot(1000, VibrationEffect.DEFAULT_AMPLITUDE))
                }
                Chip("PATTERN", false, Modifier.weight(1f)) {
                    vib?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 200, 120, 200, 120, 500), -1))
                }
            }
        }
    }
}

// ---------------------------------------------------------------------- internet

private fun ms(t0: Long) = (System.nanoTime() - t0) / 1_000_000.0

@Composable
private fun InternetTest(back: () -> Unit) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("READY") }
    var rows by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }

    fun run() {
        if (busy) return
        busy = true
        rows = emptyList()
        scope.launch {
            val out = ArrayList<Pair<String, String>>()
            withContext(Dispatchers.IO) {
                status = "DNS..."
                try {
                    val t0 = System.nanoTime()
                    InetAddress.getByName("www.google.com")
                    out.add("DNS LOOKUP" to "${f0(ms(t0).toFloat())} ms")
                } catch (e: Exception) { out.add("DNS LOOKUP" to "FAILED") }
                rows = out.toList()

                status = "LATENCY..."
                val lat = ArrayList<Double>()
                for (i in 0 until 5) {
                    try {
                        val t0 = System.nanoTime()
                        val c = URL("https://www.gstatic.com/generate_204").openConnection() as HttpURLConnection
                        c.connectTimeout = 5000
                        c.readTimeout = 5000
                        c.responseCode
                        c.disconnect()
                        lat.add(ms(t0))
                    } catch (e: Exception) { }
                }
                if (lat.isEmpty()) out.add("LATENCY" to "FAILED") else {
                    out.add("LATENCY (avg / min / max)" to "${f0(lat.average().toFloat())} / ${f0(lat.min().toFloat())} / ${f0(lat.max().toFloat())} ms")
                    out.add("JITTER" to "${f0((lat.max() - lat.min()).toFloat())} ms")
                }
                rows = out.toList()

                status = "DOWNLOAD..."
                try {
                    val c = URL("https://speed.cloudflare.com/__down?bytes=10000000").openConnection() as HttpURLConnection
                    c.connectTimeout = 6000
                    c.readTimeout = 8000
                    val t0 = System.nanoTime()
                    var total = 0L
                    c.inputStream.use { s ->
                        val b = ByteArray(65536)
                        while (true) {
                            val n = s.read(b)
                            if (n < 0 || ms(t0) > 10000) break
                            total += n
                        }
                    }
                    val sec = ms(t0) / 1000.0
                    out.add("DOWNLOAD SPEED" to "${f1((total * 8 / sec / 1e6).toFloat())} Mbps")
                } catch (e: Exception) { out.add("DOWNLOAD SPEED" to "FAILED") }
                rows = out.toList()

                status = "UPLOAD..."
                try {
                    val size = 3_000_000
                    val c = URL("https://speed.cloudflare.com/__up").openConnection() as HttpURLConnection
                    c.requestMethod = "POST"
                    c.doOutput = true
                    c.connectTimeout = 6000
                    c.readTimeout = 10000
                    c.setFixedLengthStreamingMode(size)
                    val t0 = System.nanoTime()
                    c.outputStream.use { o ->
                        val b = ByteArray(65536)
                        var left = size
                        while (left > 0) {
                            val n = minOf(left, b.size)
                            o.write(b, 0, n)
                            left -= n
                        }
                    }
                    c.responseCode
                    val sec = ms(t0) / 1000.0
                    out.add("UPLOAD SPEED" to "${f1((size * 8 / sec / 1e6).toFloat())} Mbps")
                    c.disconnect()
                } catch (e: Exception) { out.add("UPLOAD SPEED" to "FAILED") }
                rows = out.toList()
            }
            status = "DONE"
            busy = false
        }
    }

    Page {
        BackRow("INTERNET TEST", back)
        Panel("SPEED AND LATENCY") {
            KV("STATUS", status, if (busy) cWarn else cGood)
            for ((k, v) in rows) KV(k, v, if (v == "FAILED") cBad else cText)
            Chip(if (busy) "TESTING..." else "START TEST", false, Modifier.fillMaxWidth().padding(top = 10.dp)) { run() }
            T("Uses about 13 MB of data. Servers: Google and Cloudflare.", 9f, cSub, lines = 2, mod = Modifier.padding(top = 6.dp))
        }
    }
}

// ---------------------------------------------------------------------- features

@Composable
private fun FeaturesTest(back: () -> Unit) {
    val ctx = LocalContext.current
    val pm = ctx.packageManager
    val list = listOf(
        "NFC" to "android.hardware.nfc", "NFC CARD EMULATION" to "android.hardware.nfc.hce",
        "FINGERPRINT" to "android.hardware.fingerprint", "FACE UNLOCK" to "android.hardware.biometrics.face",
        "TELEPHONY" to "android.hardware.telephony", "WI-FI" to "android.hardware.wifi",
        "WI-FI DIRECT" to "android.hardware.wifi.direct", "WI-FI AWARE" to "android.hardware.wifi.aware",
        "BLUETOOTH" to "android.hardware.bluetooth", "BLUETOOTH LE" to "android.hardware.bluetooth_le",
        "GPS" to "android.hardware.location.gps", "FRONT CAMERA" to "android.hardware.camera.front",
        "CAMERA FLASH" to "android.hardware.camera.flash", "ACCELEROMETER" to "android.hardware.sensor.accelerometer",
        "GYROSCOPE" to "android.hardware.sensor.gyroscope", "COMPASS" to "android.hardware.sensor.compass",
        "BAROMETER" to "android.hardware.sensor.barometer", "LIGHT SENSOR" to "android.hardware.sensor.light",
        "PROXIMITY" to "android.hardware.sensor.proximity", "STEP COUNTER" to "android.hardware.sensor.stepcounter",
        "HEART RATE" to "android.hardware.sensor.heartrate", "USB HOST (OTG)" to "android.hardware.usb.host",
        "INFRARED (IR)" to "android.hardware.consumerir", "MULTI-TOUCH" to "android.hardware.touchscreen.multitouch",
        "MICROPHONE" to "android.hardware.microphone"
    )
    Page {
        BackRow("HARDWARE FEATURES", back)
        Panel("SUPPORT") {
            @Suppress("DEPRECATION")
            val gl = (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).deviceConfigurationInfo.glEsVersion
            KV("OPENGL ES", gl)
            for ((n, f) in list) {
                val ok = pm.hasSystemFeature(f)
                KV(n, if (ok) "YES" else "NO", if (ok) cGood else cSub)
            }
        }
    }
}
