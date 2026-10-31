package com.device.mux.manager

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun HomeScreen() {
    Page {
        T("DEVICE OVERVIEW", 16f, cMain, true)
        Summary()
        TwoCol(
            { CpuPanel(); CoresPanel(); ThermalPanel() },
            { RamPanel(); StoragePanel(); DevicePanel() }
        )
    }
}

@Composable
private fun Summary() {
    val r = Monitor.ram
    val rf = if (r != null && r.total > 0) 1f - r.avail.toFloat() / r.total else 0f
    val s = Monitor.storage.firstOrNull()
    val sf = if (s != null && s.total > 0) 1f - s.free.toFloat() / s.total else 0f
    val b = Monitor.batt
    val bl = (b?.level ?: 0) / 100f
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        Ring(Monitor.cpuLoad, "CPU", fmtPct(Monitor.cpuLoad), loadColor(Monitor.cpuLoad))
        Ring(rf, "RAM", fmtPct(rf), loadColor(rf))
        Ring(sf, "STORAGE", fmtPct(sf), loadColor(sf))
        Ring(bl, "BATTERY", "${b?.level ?: 0}%", if (bl < 0.2f) cBad else cGood)
    }
}

@Composable
private fun CpuPanel() {
    val c = Monitor.cpuStatic
    Panel("PROCESSOR") {
        if (c == null) {
            T("READING...", 11f, cSub)
            return@Panel
        }
        KV("SOC / CPU", c.name)
        KV("HARDWARE", c.hardware)
        KV("ARCHITECTURE", c.abi)
        KV("CORES", "${c.cores}")
        KV("THREADS", "${Runtime.getRuntime().availableProcessors()} (1 per core on ARM)")
        KV("CLUSTERS", c.clusters)
        KV("GOVERNOR", c.governor)
        KV("CPU LOAD", fmtPct(Monitor.cpuLoad), loadColor(Monitor.cpuLoad))
        KV("CPU TEMP", if (Monitor.cpuTemp.isNaN()) "N/A" else "${f1(Monitor.cpuTemp)} °C", tempColor(Monitor.cpuTemp))
        T("TEMP SOURCE: ${Monitor.cpuTempSource}", 9f, cSub, lines = 2)
        T("LOAD SOURCE: ${Monitor.loadSource}", 9f, cSub, lines = 2, mod = Modifier.padding(bottom = 8.dp))
        T("CPU LOAD %", 10f, cSub)
        LineGraph(listOf(Series(Monitor.cpuHist, cMain)), 0f, 100f, "%")
        T("CPU TEMP °C", 10f, cSub, mod = Modifier.padding(top = 8.dp))
        LineGraph(listOf(Series(Monitor.cpuTempHist, cWarn)), unit = "°")
    }
}

@Composable
private fun CoresPanel() {
    Panel("LIVE CORES") {
        if (Monitor.cores.isEmpty()) T("READING...", 11f, cSub)
        for (c in Monitor.cores) {
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                T("CPU${c.idx}", 11f, if (c.online) cMain else cSub, true, Modifier.width(48.dp))
                Column(Modifier.weight(1f)) {
                    Bar(c.load, loadColor(c.load))
                    T(
                        if (!c.online) "OFFLINE" else if (c.freqKhz > 0) "${c.freqKhz / 1000} / ${c.maxKhz / 1000} MHz" else "-- MHz",
                        9f, cSub
                    )
                }
                T("${(c.load * 100).toInt()}%", 11f, cText, mod = Modifier.width(40.dp), align = TextAlign.End)
                val t = c.tempC
                T(if (t != null) "${f0(t)}°C" else "--", 11f, if (t != null) tempColor(t) else cSub, mod = Modifier.width(48.dp), align = TextAlign.End)
            }
        }
        T("Per-core temperature is shown only where the phone exposes a matching thermal sensor.", 9f, cSub, lines = 3, mod = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun ThermalPanel() {
    Panel("THERMAL ZONES") {
        val z = Monitor.zones
        if (z.isEmpty()) T("NO THERMAL ZONE READABLE ON THIS DEVICE", 11f, cSub, lines = 2)
        for (zone in z.take(16)) KV(zone.name, "${f1(zone.tempC)} °C", tempColor(zone.tempC))
    }
}

@Composable
private fun RamPanel() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf("") }
    val r = Monitor.ram
    Panel("MEMORY (RAM)") {
        if (r == null) {
            T("READING...", 11f, cSub)
            return@Panel
        }
        val used = r.total - r.avail
        PieWithLegend(
            listOf(
                Slice("USED", used.toFloat(), cWarn, fmtBytes(used)),
                Slice("FREE", r.avail.toFloat(), cMain, fmtBytes(r.avail))
            ),
            fmtPct(used.toFloat() / r.total.coerceAtLeast(1))
        )
        KV("TOTAL", fmtBytes(r.total))
        KV("CACHED", fmtBytes(r.cached))
        KV("LOW MEMORY LIMIT", fmtBytes(r.threshold))
        KV("SWAP", if (r.swapTotal > 0) "${fmtBytes(r.swapTotal - r.swapFree)} / ${fmtBytes(r.swapTotal)}" else "NONE")
        KV("STATE", if (r.low) "LOW MEMORY" else "OK", if (r.low) cBad else cGood)
        T("RAM USAGE %", 10f, cSub, mod = Modifier.padding(top = 8.dp))
        LineGraph(listOf(Series(Monitor.ramHist, cWarn)), 0f, 100f, "%")
        Chip(if (msg == "BUSY") "CLEANING..." else "RAM CLEAN", false, Modifier.fillMaxWidth().padding(top = 10.dp)) {
            if (msg != "BUSY") scope.launch {
                msg = "BUSY"
                val freed = Cleaner.ramClean(ctx)
                msg = "FREED ${fmtBytes(freed)}"
            }
        }
        if (msg.isNotEmpty() && msg != "BUSY") T(msg, 11f, cGood, mod = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun StoragePanel() {
    val ctx = LocalContext.current
    Panel("STORAGE") {
        if (Monitor.storage.isEmpty()) T("READING...", 11f, cSub)
        for (s in Monitor.storage) {
            val used = s.total - s.free
            T(s.label, 11f, cSub, true, Modifier.padding(bottom = 6.dp))
            PieWithLegend(
                listOf(
                    Slice("USED", used.toFloat(), cWarn, fmtBytes(used)),
                    Slice("FREE", s.free.toFloat(), cGood, fmtBytes(s.free))
                ),
                fmtPct(used.toFloat() / s.total.coerceAtLeast(1))
            )
            KV("TOTAL", fmtBytes(s.total))
            Column(Modifier.padding(bottom = 8.dp)) {}
        }
        Chip("SYSTEM STORAGE SETTINGS", false, Modifier.fillMaxWidth()) {
            try {
                ctx.startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) { }
        }
    }
}

@Composable
private fun DevicePanel() {
    val ctx = LocalContext.current
    val dm = ctx.resources.displayMetrics
    @Suppress("DEPRECATION")
    val hz = try { (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.refreshRate } catch (e: Exception) { 0f }
    Panel("DEVICE") {
        KV("MODEL", "${Build.MANUFACTURER} ${Build.MODEL}")
        KV("CODENAME", Build.DEVICE)
        KV("ANDROID", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        if (Build.VERSION.SDK_INT >= 23) KV("SECURITY PATCH", Build.VERSION.SECURITY_PATCH)
        KV("KERNEL", System.getProperty("os.version") ?: "N/A")
        KV("BUILD", Build.DISPLAY)
        KV("DISPLAY", "${dm.widthPixels} x ${dm.heightPixels}  ${dm.densityDpi} dpi")
        KV("REFRESH RATE", "${f0(hz)} Hz")
        KV("UPTIME", fmtDuration(SystemClock.elapsedRealtime()))
    }
}
