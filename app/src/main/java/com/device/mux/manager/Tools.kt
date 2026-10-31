package com.device.mux.manager

import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.sqrt

enum class Tool { MENU, CLEAN, APPS, APKS, MAG, TESTS }

@Composable
fun ToolsScreen(tool: Tool, setTool: (Tool) -> Unit) {
    val back = { setTool(Tool.MENU) }
    when (tool) {
        Tool.MENU -> ToolMenu(setTool)
        Tool.CLEAN -> CleanScreen(back)
        Tool.APPS -> AppsScreen(back)
        Tool.APKS -> ApksScreen(back)
        Tool.MAG -> MagneticScreen(back)
        Tool.TESTS -> TestsScreen(back)
    }
}

@Composable
private fun MenuItem(title: String, desc: String, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().border(1.dp, cDim, HudShape(8.dp)).clickable(onClick = onClick).padding(14.dp)
    ) {
        T(title, 14f, cMain, true)
        T(desc, 10f, cSub, lines = 2, mod = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun ToolMenu(setTool: (Tool) -> Unit) {
    Page {
        T("TOOLS", 16f, cMain, true)
        MenuItem("CLEANER", "RAM clean, junk files clean, temp files clean") { setTool(Tool.CLEAN) }
        MenuItem("APPS", "Installed apps, open, info, backup APK, uninstall") { setTool(Tool.APPS) }
        MenuItem("APK FILES", "Every .apk on your storage. Install or delete") { setTool(Tool.APKS) }
        MenuItem("MAGNETIC FIELD", "Live magnetometer with beep alarm for strong fields") { setTool(Tool.MAG) }
        MenuItem("HARDWARE TESTS", "Display, touch, speaker, mic, sensors, buttons, cameras, vibration, internet") { setTool(Tool.TESTS) }
    }
}

@Composable
fun BackRow(title: String, back: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Chip("< BACK", false, Modifier.size(width = 86.dp, height = 40.dp), back)
        T(title, 16f, cMain, true)
    }
}

// ---------------------------------------------------------------------- cleaner

@Composable
private fun CleanScreen(back: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var ramMsg by remember { mutableStateOf("") }
    Page {
        BackRow("CLEANER", back)
        Panel("RAM CLEAN") {
            val r = Monitor.ram
            if (r != null) {
                PieWithLegend(
                    listOf(
                        Slice("USED", (r.total - r.avail).toFloat(), cWarn, fmtBytes(r.total - r.avail)),
                        Slice("FREE", r.avail.toFloat(), cMain, fmtBytes(r.avail))
                    ),
                    fmtPct((r.total - r.avail).toFloat() / r.total.coerceAtLeast(1))
                )
            }
            T(
                "Stops background processes of installed apps. Android decides what can be freed, so the amount varies.",
                10f, cSub, lines = 3, mod = Modifier.padding(vertical = 8.dp)
            )
            Chip(if (ramMsg == "BUSY") "CLEANING..." else "RAM CLEAN", false, Modifier.fillMaxWidth()) {
                if (ramMsg != "BUSY") scope.launch {
                    ramMsg = "BUSY"
                    ramMsg = "FREED ${fmtBytes(Cleaner.ramClean(ctx))}"
                }
            }
            if (ramMsg.isNotEmpty() && ramMsg != "BUSY") T(ramMsg, 12f, cGood, mod = Modifier.padding(top = 6.dp))
        }
        JunkPanel("JUNK FILES CLEAN", "Cache folders, thumbnails, log files, empty folders", JunkMode.JUNK)
        JunkPanel("TEMP FILES CLEAN", "tmp, bak, old, part, crdownload files and this app's cache", JunkMode.TEMP)
        Panel("OTHER APPS' CACHE") {
            T("Android does not let apps delete other apps' cache directly. Use the system screen:", 10f, cSub, lines = 3)
            Chip("OPEN SYSTEM STORAGE SETTINGS", false, Modifier.fillMaxWidth().padding(top = 8.dp)) {
                try {
                    ctx.startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) { }
            }
        }
    }
}

@Composable
private fun JunkPanel(title: String, desc: String, mode: JunkMode) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<JunkItem>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    val tick = Perms.tick
    val has = remember(tick) { Perms.allFiles(ctx) }
    Panel(title) {
        T(desc, 10f, cSub, lines = 2, mod = Modifier.padding(bottom = 8.dp))
        if (!has) {
            T("File access is needed to scan your storage.", 11f, cWarn, lines = 2)
            Chip("GRANT FILE ACCESS", false, Modifier.fillMaxWidth().padding(vertical = 6.dp)) { Perms.requestAllFiles(ctx) }
        }
        Chip(if (busy) "WORKING..." else "SCAN", false, Modifier.fillMaxWidth()) {
            if (!busy) scope.launch {
                busy = true
                msg = ""
                items = Cleaner.scan(ctx, mode)
                busy = false
            }
        }
        val list = items
        if (list != null) {
            val total = list.sumOf { it.size }
            KV("FOUND", "${list.size} items  ${fmtBytes(total)}", if (list.isEmpty()) cGood else cWarn)
            list.groupBy { it.kind }.forEach { (k, v) -> KV(k, "${v.size} items  ${fmtBytes(v.sumOf { it.size })}") }
            for (it in list.sortedByDescending { it.size }.take(6)) {
                T("${it.file.name}  ${fmtBytes(it.size)}", 9f, cSub, mod = Modifier.padding(top = 2.dp))
            }
            if (list.isNotEmpty()) {
                Chip("CLEAN ${fmtBytes(total)}", true, Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    if (!busy) scope.launch {
                        busy = true
                        val freed = Cleaner.delete(list)
                        msg = "CLEANED ${fmtBytes(freed)}"
                        items = emptyList()
                        busy = false
                    }
                }
            }
        }
        if (msg.isNotEmpty()) T(msg, 12f, cGood, mod = Modifier.padding(top = 6.dp))
    }
}

// ---------------------------------------------------------------------- apps

@Composable
private fun AppsScreen(back: () -> Unit) {
    val ctx = LocalContext.current
    var apps by remember { mutableStateOf<List<AppRow>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var filter by remember { mutableIntStateOf(0) }
    var sort by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var open by remember { mutableStateOf<String?>(null) }
    var msg by remember { mutableStateOf("") }
    val tick = Perms.tick
    LaunchedEffect(tick) {
        loading = true
        apps = Cleaner.loadApps(ctx)
        loading = false
    }
    val shown = remember(apps, filter, sort, query) {
        apps.filter { (filter == 2 || (filter == 0) != it.system) && (query.isBlank() || it.label.contains(query, true) || it.pkg.contains(query, true)) }
            .let { l ->
                when (sort) {
                    1 -> l.sortedByDescending { it.size }
                    2 -> l.sortedByDescending { it.updated }
                    else -> l.sortedBy { it.label.lowercase() }
                }
            }
    }
    Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BackRow("APPS (${shown.size})", back)
        BasicTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            textStyle = TextStyle(color = cText, fontFamily = Mono, fontSize = 14.sp),
            cursorBrush = SolidColor(cMain),
            modifier = Modifier.fillMaxWidth().border(1.dp, cDim).padding(10.dp),
            decorationBox = { inner ->
                Box { if (query.isEmpty()) T("SEARCH APP OR PACKAGE...", 12f, cSub); inner() }
            }
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("USER", "SYSTEM", "ALL").forEachIndexed { i, s -> Chip(s, filter == i, Modifier.weight(1f)) { filter = i } }
            listOf("A-Z", "SIZE", "DATE").forEachIndexed { i, s -> Chip(s, sort == i, Modifier.weight(1f)) { sort = i } }
        }
        if (msg.isNotEmpty()) T(msg, 11f, cGood, lines = 2)
        if (loading) T("LOADING...", 12f, cSub)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(shown, key = { it.pkg }) { a ->
                AppItem(a, open == a.pkg, { open = if (open == a.pkg) null else a.pkg }) { msg = it }
            }
        }
    }
}

@Composable
private fun AppItem(a: AppRow, expanded: Boolean, toggle: () -> Unit, say: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val icon = remember(a.pkg) {
        try { ctx.packageManager.getApplicationIcon(a.pkg).toBitmap(72, 72).asImageBitmap() } catch (e: Exception) { null }
    }
    Column(Modifier.fillMaxWidth().border(1.dp, cDim, HudShape(6.dp)).clickable(onClick = toggle).padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (icon != null) Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(36.dp))
            Column(Modifier.weight(1f)) {
                T(a.label, 13f, cText, true)
                T("${a.pkg}", 9f, cSub)
                T("v${a.version}  ${fmtBytes(a.size)}${if (a.system) "  SYSTEM" else ""}", 10f, cSub)
            }
        }
        if (expanded) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("OPEN", false, Modifier.weight(1f)) {
                    ctx.packageManager.getLaunchIntentForPackage(a.pkg)?.let {
                        try { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { }
                    } ?: say("NO LAUNCH SCREEN")
                }
                Chip("INFO", false, Modifier.weight(1f)) {
                    try {
                        ctx.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.pkg}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (e: Exception) { }
                }
                Chip("BACKUP", false, Modifier.weight(1f)) { scope.launch { say(Cleaner.backupApk(a)) } }
                Chip("UNINSTALL", true, Modifier.weight(1f)) {
                    try {
                        ctx.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${a.pkg}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (e: Exception) { }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------- apk files

@Composable
private fun ApksScreen(back: () -> Unit) {
    val ctx = LocalContext.current
    var list by remember { mutableStateOf<List<ApkRow>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var scanned by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val tick = Perms.tick
    val has = remember(tick) { Perms.allFiles(ctx) }
    LaunchedEffect(tick) {
        if (Perms.allFiles(ctx)) {
            loading = true
            list = Cleaner.scanApks(ctx)
            loading = false
            scanned = true
        }
    }
    Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BackRow("APK FILES (${list.size})", back)
        if (!has) {
            T("File access is needed to find APK files on your storage.", 11f, cWarn, lines = 2)
            Chip("GRANT FILE ACCESS", false, Modifier.fillMaxWidth()) { Perms.requestAllFiles(ctx) }
        } else {
            Chip(if (loading) "SCANNING..." else "RESCAN", false, Modifier.fillMaxWidth()) {
                if (!loading) scope.launch { loading = true; list = Cleaner.scanApks(ctx); loading = false; scanned = true }
            }
        }
        if (scanned && list.isEmpty()) T("NO APK FILES FOUND", 12f, cSub)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(list, key = { it.file.path }) { a ->
                val status = when {
                    a.installed == null -> "NOT INSTALLED"
                    a.installed == a.version -> "SAME VERSION INSTALLED"
                    else -> "INSTALLED: v${a.installed}  (UPDATE)"
                }
                Column(Modifier.fillMaxWidth().border(1.dp, cDim, HudShape(6.dp)).padding(10.dp)) {
                    T(a.label, 13f, cText, true)
                    T("${a.pkg}  v${a.version}", 9f, cSub, lines = 2)
                    T("${fmtBytes(a.file.length())}  ${a.file.parent ?: ""}", 9f, cSub, lines = 2)
                    T(status, 10f, if (a.installed == null) cGood else cWarn)
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Chip("INSTALL", true, Modifier.weight(1f)) { Cleaner.installApk(ctx, a.file) }
                        Chip("DELETE", false, Modifier.weight(1f)) {
                            if (a.file.delete()) list = list.filter { it.file.path != a.file.path }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------- magnetic field

@Composable
private fun MagneticScreen(back: () -> Unit) {
    val ctx = LocalContext.current
    val sm = remember { ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    val sensor = remember { sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) }
    var x by remember { mutableFloatStateOf(0f) }
    var y by remember { mutableFloatStateOf(0f) }
    var z by remember { mutableFloatStateOf(0f) }
    var mag by remember { mutableFloatStateOf(0f) }
    var peak by remember { mutableFloatStateOf(0f) }
    var thr by remember { mutableFloatStateOf(80f) }
    var armed by remember { mutableStateOf(true) }
    val hist = remember { mutableStateListOf<Float>() }
    val tone = remember { ToneGenerator(AudioManager.STREAM_MUSIC, 100) }

    DisposableEffect(sensor) {
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                x = e.values[0]; y = e.values[1]; z = e.values[2]
                mag = sqrt(x * x + y * y + z * z)
                if (mag > peak) peak = mag
                hist.add(mag)
                if (hist.size > 150) hist.removeAt(0)
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        if (sensor != null) sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm.unregisterListener(l) }
    }
    DisposableEffect(Unit) { onDispose { tone.release() } }
    LaunchedEffect(armed) {
        while (armed) {
            if (mag > thr) {
                tone.startTone(ToneGenerator.TONE_PROP_BEEP, 80)
                delay(((thr / mag) * 450f).toLong().coerceIn(90L, 450L))
            } else delay(100)
        }
    }

    Page {
        BackRow("MAGNETIC FIELD", back)
        if (sensor == null) {
            T("NO MAGNETIC SENSOR ON THIS DEVICE", 13f, cBad)
            return@Page
        }
        val high = mag > thr
        Panel("FIELD STRENGTH") {
            T("${f1(mag)} µT", 34f, if (high) cBad else cMain, true, Modifier.fillMaxWidth(), align = TextAlign.Center)
            T(
                if (high) "HIGH FIELD DETECTED" else "NORMAL (earth field is about 25 to 65 µT)",
                11f, if (high) cBad else cGood, mod = Modifier.fillMaxWidth(), align = TextAlign.Center
            )
            KV("X", "${f1(x)} µT")
            KV("Y", "${f1(y)} µT")
            KV("Z", "${f1(z)} µT")
            KV("PEAK", "${f1(peak)} µT")
            LineGraph(listOf(Series(hist, if (high) cBad else cMain)), 0f, null, " µT", 110.dp)
        }
        Panel("BEEP ALARM") {
            T("Beeps when the field goes above the threshold. Beeps get faster as the field gets stronger.", 10f, cSub, lines = 3)
            T("THRESHOLD: ${f0(thr)} µT", 12f, cText, mod = Modifier.padding(top = 8.dp))
            Slider(
                value = thr, onValueChange = { thr = it }, valueRange = 30f..500f,
                colors = SliderDefaults.colors(thumbColor = cMain, activeTrackColor = cMain, inactiveTrackColor = cDim)
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(if (armed) "BEEP: ON" else "BEEP: OFF", armed, Modifier.weight(1f)) { armed = !armed }
                Chip("RESET PEAK", false, Modifier.weight(1f)) { peak = 0f }
            }
        }
    }
}
